(() => {
  'use strict';
  const NAMESPACE = 'urn:x-cast:com.eplay.cast.compat.v1';
  const video = document.getElementById('compatVideo');
  const status = document.getElementById('status');
  const context = cast.framework.CastReceiverContext.getInstance();
  let pc = null;
  let remoteStream = null;
  let activeSenderId = null;
  let stateTimer = null;
  let offerBusy = false;

  function setStatus(text, visible = true) {
    if (!status) return;
    status.textContent = text;
    status.style.display = visible ? 'block' : 'none';
  }

  function send(data, senderId = activeSenderId) {
    if (!senderId) return;
    try { context.sendCustomMessage(NAMESPACE, senderId, data); }
    catch (e) { console.warn('[EPlay Cast Compat] send:', e); }
  }

  function waitForIceGathering(peer, timeoutMs = 8000) {
    if (peer.iceGatheringState === 'complete') return Promise.resolve();
    return new Promise(resolve => {
      let finished = false;
      const finish = () => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        resolve();
      };
      const timer = setTimeout(finish, timeoutMs);
      peer.addEventListener('icegatheringstatechange', () => {
        if (peer.iceGatheringState === 'complete') finish();
      });
    });
  }
  function stopPeer() {
    if (stateTimer) {
      clearInterval(stateTimer);
      stateTimer = null;
    }
    if (pc) {
      try { pc.ontrack = null; } catch (_) {}
      try { pc.close(); } catch (_) {}
    }
    pc = null;
    if (remoteStream) {
      remoteStream.getTracks().forEach(track => {
        try { track.stop(); } catch (_) {}
      });
    }
    remoteStream = null;
    video.srcObject = null;
    offerBusy = false;
  }

  function sendState() {
    if (!activeSenderId || !video) return;
    send({
      type: 'state',
      paused: !!video.paused,
      volume: Number(video.volume || 0),
      muted: !!video.muted
    });
  }

  async function handleOffer(message, senderId) {
    if (offerBusy) return;
    offerBusy = true;
    activeSenderId = senderId;
    setStatus('EPlay • conectando transmissão compatível…');
    try {
      stopPeer();
      activeSenderId = senderId;
      if (typeof RTCPeerConnection !== 'function') {
        throw new Error('WebRTC não está disponível neste Chromecast');
      }

      pc = new RTCPeerConnection({ iceServers: [] });
      remoteStream = new MediaStream();
      video.srcObject = remoteStream;

      pc.ontrack = event => {
        const track = event.track;
        if (!remoteStream.getTracks().some(existing => existing.id === track.id)) {
          remoteStream.addTrack(track);
        }
        video.play().catch(() => {});
      };
      pc.onconnectionstatechange = () => {
        if (!pc) return;
        if (pc.connectionState === 'connected') {
          setStatus('EPlay • transmissão compatível ativa', true);
          setTimeout(() => setStatus('', false), 1800);
        } else if (pc.connectionState === 'failed') {
          setStatus('EPlay • falha na conexão compatível');
          send({ type: 'error', message: 'WebRTC não conseguiu conectar ao computador.' });
        } else if (pc.connectionState === 'disconnected') {
          setStatus('EPlay • conexão interrompida');
        }
      };

      await pc.setRemoteDescription({ type: 'offer', sdp: String(message.sdp || '') });
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      await waitForIceGathering(pc);

      send({
        type: 'answer',
        version: 1,
        sdp: pc.localDescription?.sdp || answer.sdp
      }, senderId);

      stateTimer = setInterval(sendState, 700);
      sendState();
      setStatus('EPlay • aguardando vídeo…');
    } catch (error) {
      console.warn('[EPlay Cast Compat] offer:', error);
      stopPeer();
      send({ type: 'error', message: error?.message || 'Erro ao iniciar WebRTC.' }, senderId);
      setStatus('EPlay • não foi possível iniciar a transmissão');
    } finally {
      offerBusy = false;
    }
  }
  function handleCommand(message) {
    const command = message?.command;
    if (!video) return;
    if (command === 'play') {
      video.play().catch(() => {});
    } else if (command === 'pause') {
      video.pause();
    } else if (command === 'mute') {
      video.muted = true;
    } else if (command === 'unmute') {
      video.muted = false;
      video.volume = Math.max(0, Math.min(1, Number(message.value) || 1));
    } else if (command === 'volume') {
      video.muted = false;
      video.volume = Math.max(0, Math.min(1, Number(message.value) || 0));
    } else if (command === 'stop') {
      video.pause();
      video.srcObject = null;
      stopPeer();
      setStatus('EPlay • transmissão encerrada');
    }
    sendState();
  }

  function onMessage(event) {
    activeSenderId = event.senderId || activeSenderId;
    const message = event.data;
    if (!message || typeof message !== 'object') return;
    if (message.type === 'hello') {
      send({
        type: 'ready',
        version: 1,
        webRtc: typeof RTCPeerConnection === 'function',
        capture: true
      });
      return;
    }
    if (message.type === 'offer') {
      handleOffer(message, event.senderId);
      return;
    }
    if (message.type === 'command') {
      handleCommand(message);
    }
  }

  const options = new cast.framework.CastReceiverOptions();
  options.customNamespaces = {
    [NAMESPACE]: cast.framework.system.MessageType.JSON
  };
  options.mediaElement = video;
  options.skipPlayersLoad = true;
  options.maxInactivity = 30;
  context.addCustomMessageListener(NAMESPACE, onMessage);
  context.start(options);
  context.setApplicationState('EPlay • Compatibilidade Chromecast');

  video.addEventListener('pause', sendState);
  video.addEventListener('play', sendState);
  video.addEventListener('volumechange', sendState);
  video.addEventListener('loadedmetadata', () => setStatus('EPlay • recebendo vídeo…'));

  window.addEventListener('unload', stopPeer);
})();
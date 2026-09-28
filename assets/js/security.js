(function initZeroAdsShield() {
      // URLs do próprio site devem ser comparadas por origin, nunca por substring.
      // Isso impede falsos positivos como "attacker.example/?ref=andsouzam.github.io".
      const isSameOriginUrl = function (rawHref) {
        const href = String(rawHref || '').trim();
        if (!href || href.startsWith('#')) return true;
        if (/^javascript:/i.test(href)) return false;
        try {
          return new URL(href, window.location.href).origin === window.location.origin;
        } catch (e) {
          return false;
        }
      };

      // 1. Bloqueia qualquer tentativa de abertura de popups / popunders com trava definitiva
      const blockPopup = function (url) {
        console.warn('[AdShield] Bloqueado popup/popunder externo:', url);
        return null;
      };
      try {
        window.open = blockPopup;
        Object.defineProperty(window, 'open', {
          configurable: false,
          writable: false,
          value: blockPopup
        });
      } catch (e) {
        window.open = blockPopup;
      }

      // 2. Bloqueia cliques em links externos ou com target="_blank"
      //    Exceção: elementos marcados com [data-adshield-allow] são funcionalidades
      //    legítimas do próprio app (ex: botão "Baixar Vídeo", que aponta para o
      //    servidor de streaming, um domínio diferente do site, mas não é um anúncio).
      document.addEventListener('click', function (e) {
        const a = e.target && e.target.closest ? e.target.closest('a') : null;
        if (a) {
          if (a.hasAttribute('data-adshield-allow')) return; // link legítimo do app
          const href = String(a.href || '').trim();
          const isSafe = isSameOriginUrl(href);
          if (!isSafe || a.target === '_blank') {
            console.warn('[AdShield] Bloqueado clique em link externo / popup:', href);
            e.preventDefault();
            e.stopPropagation();
          }
        }
      }, true);

      // 3. Bloqueia cliques automáticos disparados por scripts maliciosos de anúncios
      const _origClick = HTMLAnchorElement.prototype.click;
      HTMLAnchorElement.prototype.click = function () {
        if (this.hasAttribute && this.hasAttribute('data-adshield-allow')) {
          return _origClick.apply(this, arguments); // link legítimo do app
        }
        const href = String(this.href || '').trim();
        const isSafe = isSameOriginUrl(href);
        if (!isSafe) {
          console.warn('[AdShield] Bloqueado clique automático em anúncio:', href);
          return;
        }
        return _origClick.apply(this, arguments);
      };

      // 4. Suprime alertas invasivos gerados por ad networks
      window.alert = function () { };
      window.confirm = function () { return false; };
      window.prompt = function () { return null; };
    })();

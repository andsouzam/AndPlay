package com.andplay.app.epg;

import com.andplay.app.model.Channel;
import com.andplay.app.model.LiveSchedule;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class EpgEngine {

    public static class ScheduleItem {
        public String s;
        public String e;
        public String t;
        public String syn;

        public ScheduleItem(String s, String e, String t, String syn) {
            this.s = s;
            this.e = e;
            this.t = t;
            this.syn = syn;
        }
    }

    public static LiveSchedule getLiveSchedule(Channel ch) {
        if (ch == null) return null;
        String rawId = (ch.id != null ? ch.id.toLowerCase().replaceFirst("^live_ch_", "") : "");
        String cat = (ch.cat != null ? ch.cat.toLowerCase() : "");
        String chName = (ch.name != null ? ch.name.toLowerCase() : "");

        Calendar cal = Calendar.getInstance();
        int day = cal.get(Calendar.DAY_OF_WEEK); // Calendar.SUNDAY = 1, Calendar.SATURDAY = 7
        int hours = cal.get(Calendar.HOUR_OF_DAY);
        int mins = cal.get(Calendar.MINUTE);
        int nowM = hours * 60 + mins;

        List<ScheduleItem> grid = new ArrayList<>();

        // 1. GLOBO
        if (rawId.contains("globo") && !rawId.contains("globonews") && !rawId.contains("gloob") && !rawId.contains("globoplay")) {
            if (day == Calendar.SUNDAY) {
                grid.add(new ScheduleItem("06:00", "07:00", "Santa Missa", "Momento de fé, oração e comunhão com a celebração da Santa Missa."));
                grid.add(new ScheduleItem("07:00", "08:30", "Globo Rural", "Reportagens completas sobre o agronegócio, campo e meio ambiente."));
                grid.add(new ScheduleItem("08:30", "09:30", "Auto Esporte", "Testes, lançamentos automobilísticos e novidades do mundo automotor."));
                grid.add(new ScheduleItem("09:30", "12:30", "Esporte Espetacular", "Grandes reportagens esportivas, quadros radicais e cobertura ao vivo."));
                grid.add(new ScheduleItem("12:30", "14:15", "Temperatura Máxima", "Superproduções do cinema mundial em alta definição para o almoço de domingo."));
                grid.add(new ScheduleItem("14:15", "15:40", "Domingão com Huck - 1ª Parte", "Luciano Huck com games eletrizantes, homenagens e grandes atrações."));
                grid.add(new ScheduleItem("15:40", "18:00", "Futebol 2026: Brasileirão Ao Vivo", "A emoção do futebol ao vivo com narração de alto nível e cobertura exclusiva."));
                grid.add(new ScheduleItem("18:00", "20:30", "Domingão com Huck - 2ª Parte", "A Dança dos Famosos, Quem Quer Ser Um Milionário e muito entretenimento."));
                grid.add(new ScheduleItem("20:30", "23:25", "Fantástico", "O show da vida com reportagens investigativas, tecnologia e os fatos da semana."));
                grid.add(new ScheduleItem("23:25", "25:15", "Domingo Maior", "Sessão de cinema com muita ação, suspense e adrenalina no fim de domingo."));
                grid.add(new ScheduleItem("25:15", "27:00", "Cinemaço", "Grandes clássicos e sucessos consagrados do cinema internacional."));
                grid.add(new ScheduleItem("27:00", "30:00", "Corujão do Domingo", "Filmes selecionados para os telespectadores da madrugada."));
            } else if (day == Calendar.SATURDAY) {
                grid.add(new ScheduleItem("06:00", "07:30", "Globo Comunidade", "Debate dos principais temas sociais, culturais e comunitários."));
                grid.add(new ScheduleItem("07:30", "11:45", "É de Casa", "Dicas de culinária, jardinagem, decoração e bem-estar nas manhãs de sábado."));
                grid.add(new ScheduleItem("11:45", "13:00", "Praça TV 1ª Edição", "Notícias locais ao vivo, trânsito e prestação de serviços no seu estado."));
                grid.add(new ScheduleItem("13:00", "14:10", "Globo Esporte & Jornal Hoje", "O balanço esportivo do sábado e as últimas notícias do Brasil e do mundo."));
                grid.add(new ScheduleItem("14:10", "18:25", "Caldeirão com Mion", "Marcos Mion com muita energia, convidados especiais, música e o Sobe o Som."));
                grid.add(new ScheduleItem("18:25", "19:15", "Novela das Seis", "Aventuras e emoções na faixa de novelas das 18h da TV Globo."));
                grid.add(new ScheduleItem("19:15", "19:45", "Praça TV 2ª Edição", "Resumo dos acontecimentos mais importantes do sábado na sua região."));
                grid.add(new ScheduleItem("19:45", "20:30", "Novela das Sete", "Comédia, romance e união na novela das sete da TV Globo."));
                grid.add(new ScheduleItem("20:30", "21:20", "Jornal Nacional", "O resumo completo e equilibrado dos fatos que marcaram o sábado no país."));
                grid.add(new ScheduleItem("21:20", "22:25", "Novela das Nove: Vale Tudo", "A grande produção do horário nobre global com revelações bombásticas."));
                grid.add(new ScheduleItem("22:25", "24:30", "Supercine", "O filme principal das noites de sábado em alta definição."));
                grid.add(new ScheduleItem("24:30", "26:15", "Corujão I", "Grandes produções do cinema mundial nas madrugadas da Globo."));
                grid.add(new ScheduleItem("26:15", "30:00", "Corujão II", "Filmes consagrados de aventura, romance e comédia até o amanhecer."));
            } else {
                grid.add(new ScheduleItem("06:00", "08:30", "Bom Dia Brasil", "As primeiras e mais importantes notícias da manhã em todo o país."));
                grid.add(new ScheduleItem("08:30", "09:30", "Encontro com Patrícia Poeta", "Debates, música, prestação de serviços e temas que movem a sociedade."));
                grid.add(new ScheduleItem("09:30", "10:45", "Mais Você com Ana Maria Braga", "Receitas deliciosas, o Louro Mané e conversas descontraídas com famosos."));
                grid.add(new ScheduleItem("10:45", "13:00", "Praça TV 1ª Edição", "Jornalismo local dinâmico com helicóptero ao vivo e prestação de serviços."));
                grid.add(new ScheduleItem("13:00", "13:25", "Globo Esporte", "Gols da rodada, bastidores dos clubes e entrevistas com os craques."));
                grid.add(new ScheduleItem("13:25", "14:45", "Jornal Hoje com César Tralli", "Notícias do dia a dia, economia popular e os fatos mais relevantes da tarde."));
                grid.add(new ScheduleItem("14:45", "15:35", "Edição Especial: Cabocla", "Clássico da teledramaturgia que emociona gerações no início da tarde."));
                grid.add(new ScheduleItem("15:35", "17:05", "Sessão da Tarde", "O clássico do cinema diário na TV aberta reunindo família e diversão."));
                grid.add(new ScheduleItem("17:05", "18:25", "Vale a Pena Ver de Novo: Alma Gêmea", "As maiores audiências da televisão brasileira de volta à tela da Globo."));
                grid.add(new ScheduleItem("18:25", "19:15", "Novela das Seis", "Aventuras e emoções na faixa de novelas das 18h da TV Globo."));
                grid.add(new ScheduleItem("19:15", "19:45", "Praça TV 2ª Edição", "Balanço do início da noite com trânsito, polícia e notícias do estado."));
                grid.add(new ScheduleItem("19:45", "20:30", "Novela das Sete", "Comédia, romance e união na novela das sete da TV Globo."));
                grid.add(new ScheduleItem("20:30", "21:20", "Jornal Nacional", "William Bonner e Renata Vasconcellos com o panorama factual do Brasil e do mundo."));
                grid.add(new ScheduleItem("21:20", "22:25", "Novela das Nove: Vale Tudo", "A novela mais assistida do país no auge dos seus conflitos dramáticos."));
                grid.add(new ScheduleItem("22:25", "23:45", "Linha de Shows / Futebol Ao Vivo", "Grandes atrações especiais, reality shows ou transmissões esportivas."));
                grid.add(new ScheduleItem("23:45", "24:35", "Jornal da Globo com Renata Lo Prete", "Análise lúcida do cenário político-econômico e fechamento do dia."));
                grid.add(new ScheduleItem("24:35", "25:20", "Conversa com Bial", "Entrevistas inteligentes e aprofundadas com personalidades marcantes."));
                grid.add(new ScheduleItem("25:20", "30:00", "Rede BBB / Corujão", "Filmes selecionados para os telespectadores da madrugada."));
            }
        }
        // 2. SBT
        else if (rawId.contains("sbt")) {
            grid.add(new ScheduleItem("06:00", "09:30", "Primeiro Impacto", "Jornalismo policial e factual com reportagens ágeis nas primeiras horas do dia."));
            grid.add(new ScheduleItem("09:30", "11:30", "Chega Mais", "Entretenimento, comportamento, saúde, culinária e prestação de serviço."));
            grid.add(new ScheduleItem("11:30", "13:30", "Chega Mais Notícias", "As principais notícias do Brasil e a prestação de serviços regional."));
            grid.add(new ScheduleItem("13:30", "14:30", "Carinha de Anjo", "Novela infantil divertida para reunir os pequenos e toda a família."));
            grid.add(new ScheduleItem("14:30", "15:30", "Quando Me Apaixono", "Drama intenso, paixões arrebatadoras e reviravoltas na novela mexicana."));
            grid.add(new ScheduleItem("15:30", "16:45", "Fofocalizando", "Notícias das celebridades, fofocas quentes dos bastidores e convidados no estúdio."));
            grid.add(new ScheduleItem("16:45", "17:45", "Meu Caminho é Te Amar", "Trama mexicana emocionante com romances e intrigas envolventes."));
            grid.add(new ScheduleItem("17:45", "19:45", "Tá na Hora", "Datena e repórteres ao vivo mostrando o Brasil real direto das ruas."));
            grid.add(new ScheduleItem("19:45", "20:30", "SBT Brasil com César Filho", "Telejornal com credibilidade, reportagens exclusivas e opinião de especialistas."));
            grid.add(new ScheduleItem("20:30", "21:30", "A Caverna Encantada", "A grande produção infanto-juvenil do SBT cheia de mistério e imaginação."));
            grid.add(new ScheduleItem("21:30", "22:15", "As Aventuras de Poliana", "A história de Poliana e o jogo do contente que conquistou o Brasil."));
            grid.add(new ScheduleItem("22:15", "23:45", "Programa do Ratinho", "Carlos Massa com jogos, calouros, comédia, testes de DNA e muita alegria."));
            grid.add(new ScheduleItem("23:45", "25:00", "The Noite com Danilo Gentili", "O talk show mais premiado da TV brasileira com humor ácido e grandes convidados."));
            grid.add(new ScheduleItem("25:00", "26:00", "Operação Mesquita", "Otávio Mesquita em reportagens curiosas e inusitadas pela madrugada."));
            grid.add(new ScheduleItem("26:00", "30:00", "SBT PodNight", "Os podcasts mais assistidos da internet agora na televisão aberta."));
        }
        // 3. RECORD
        else if (rawId.contains("record") && !rawId.contains("record-news")) {
            grid.add(new ScheduleItem("06:00", "08:45", "Balanço Geral Manhã", "Prestação de serviço, trânsito, helicóptero e notícias policiais da manhã."));
            grid.add(new ScheduleItem("08:45", "10:00", "Fala Brasil", "Telejornal matinal com reportagens investigativas e giro internacional."));
            grid.add(new ScheduleItem("10:00", "11:50", "Hoje em Dia", "Celso Zucatelli, Ana Hickmann e Ticiane Pinheiro com variedades e moda."));
            grid.add(new ScheduleItem("11:50", "15:30", "Balanço Geral SP & A Hora da Venenosa", "Reinaldo Gottino e Fabíola Reipert com as notícias quentes dos famosos."));
            grid.add(new ScheduleItem("15:30", "16:45", "Apocalipse: Edição Especial", "Superprodução épica bíblica com fortes emoções e lições de fé."));
            grid.add(new ScheduleItem("16:45", "19:55", "Cidade Alerta com Luiz Bacci", "Cobertura policial detalhada, grandes resgates e helicópteros ao vivo."));
            grid.add(new ScheduleItem("19:55", "20:45", "Jornal da Record com Celso Freitas", "O jornalismo investigativo de referência da Record com reportagens especiais."));
            grid.add(new ScheduleItem("20:45", "21:45", "Força de Mulher", "O fenômeno turco que conquistou o público brasileiro com drama e superação."));
            grid.add(new ScheduleItem("21:45", "22:45", "Gênesis / Reis", "Série bíblica superproduzida com efeitos visuais e reconstituição histórica."));
            grid.add(new ScheduleItem("22:45", "24:00", "A Fazenda 16 / Linha de Shows", "Reality show com os peões mais polêmicos do Brasil confinados na fazenda."));
            grid.add(new ScheduleItem("24:00", "25:00", "Chicago Med / Chicago P.D.", "Série médica e policial norte-americana em alta definição."));
            grid.add(new ScheduleItem("25:00", "30:00", "Jornal da Record 24H", "Giro completo das manchetes para os telespectadores da madrugada."));
        }
        // 4. BAND
        else if (rawId.contains("band") && !rawId.contains("bandnews") && !rawId.contains("bandsports")) {
            grid.add(new ScheduleItem("06:00", "08:00", "Bora Brasil", "Informações ágeis de trânsito, clima e as primeiras notícias da manhã."));
            grid.add(new ScheduleItem("08:00", "11:00", "Dia Dia", "Dicas úteis de culinária, bem-estar, saúde e estilo de vida."));
            grid.add(new ScheduleItem("11:00", "13:00", "Jogo Aberto com Renata Fan", "Renata Fan e Denílson Show com debates descontraídos e gols da rodada."));
            grid.add(new ScheduleItem("13:00", "14:30", "Os Donos da Bola com Craque Neto", "Opiniões contundentes, polêmicas e bom humor sobre o futebol brasileiro."));
            grid.add(new ScheduleItem("14:30", "16:00", "Melhor da Tarde com Catia Fonseca", "Receitas saborosas, fofocas quentes dos artistas e convidados no estúdio."));
            grid.add(new ScheduleItem("16:00", "19:20", "Brasil Urgente com Datena", "José Luiz Datena cobrindo os fatos policiais e sociais que comovem o país."));
            grid.add(new ScheduleItem("19:20", "20:30", "Jornal da Band com Eduardo Oinegue", "Análises aprofundadas, colunistas de renome e credibilidade jornalística."));
            grid.add(new ScheduleItem("20:30", "22:00", "Perrengue do Dia", "Humor com a turma do Perrengue mostrando os vídeos mais hilários da internet."));
            grid.add(new ScheduleItem("22:00", "24:00", "MasterChef Brasil / Sessão Especial", "A maior competição gastronômica da TV brasileira com jurados estrelados."));
            grid.add(new ScheduleItem("24:00", "25:00", "Jornal da Noite", "O resumo reflexivo e crítico dos acontecimentos do dia."));
            grid.add(new ScheduleItem("25:00", "30:00", "Esporte Total", "Todos os esportes olímpicos, automobilismo e resumos da rodada."));
        }
        // 5. SPORTV
        else if (rawId.contains("sportv")) {
            grid.add(new ScheduleItem("00:00", "06:00", "Madrugada SporTV: Compactos e Gols", "Melhores momentos dos principais jogos da rodada e especiais do acervo."));
            grid.add(new ScheduleItem("06:00", "09:00", "SporTV News - 1ª Edição", "Informações matinais sobre os clubes de futebol, treinos e preparação dos atletas."));
            grid.add(new ScheduleItem("09:00", "12:30", "Redação SporTV com Marcelo Barreto", "Os temas esportivos da atualidade debatidos com jornalistas de capitais."));
            grid.add(new ScheduleItem("12:30", "15:30", "Seleção SporTV com André Rizek", "Debate tático sobre escalações, mercado da bola e lances polêmicos."));
            grid.add(new ScheduleItem("15:30", "18:00", "Tá na Área", "Apresentação jovem e descontraída com repórteres ao vivo nos gramados."));
            grid.add(new ScheduleItem("18:00", "21:30", "Futebol Ao Vivo / Transmissão Oficial HD", "Transmissão da rodada do campeonato brasileiro com pré-jogo completo."));
            grid.add(new ScheduleItem("21:30", "23:00", "Troca de Passes com Alex Escobar", "Análise tática dos lances polêmicos, entrevistas pós-jogo e estatísticas."));
            grid.add(new ScheduleItem("23:00", "25:00", "Boleiragem com Roger Flores", "Ex-jogadores e atletas da atualidade em resenha descontraída de vestiário."));
            grid.add(new ScheduleItem("25:00", "30:00", "SporTV News - Edição Noite", "O fechamento de todas as notícias esportivas da rodada nacional e internacional."));
        }
        // 6. ESPN
        else if (rawId.contains("espn")) {
            grid.add(new ScheduleItem("00:00", "06:00", "SportsCenter Madrugada", "Compacto dos melhores momentos do futebol europeu, NBA e NFL."));
            grid.add(new ScheduleItem("06:00", "09:00", "SportsCenter Bom Dia", "Manchetes do futebol internacional e preparação para a rodada europeia."));
            grid.add(new ScheduleItem("09:00", "12:00", "ESPN F90 Brasil", "Mesa redonda acalorada com opiniões sinceras sobre o futebol brasileiro."));
            grid.add(new ScheduleItem("12:00", "15:00", "SportsCenter - Edição Almoço", "Informações ao vivo com correspondentes internacionais em Londres e Madri."));
            grid.add(new ScheduleItem("15:00", "17:30", "Futebol no Mundo: Premier League & La Liga", "Cobertura da Premier League inglesa, La Liga espanhola e Champions League."));
            grid.add(new ScheduleItem("17:30", "20:00", "ESPN F360", "Giro 360 graus por todos os esportes, estatísticas avançadas e análise tática."));
            grid.add(new ScheduleItem("20:00", "22:30", "SportsCenter - Edição Principal", "Apresentação das notícias mais importantes do esporte mundial em alta definição."));
            grid.add(new ScheduleItem("22:30", "25:00", "Linha de Passe com Mauro Cezar", "A tradicional mesa redonda pós-rodada debatendo cada lance com rigor analítico."));
            grid.add(new ScheduleItem("25:00", "30:00", "ESPN Knockout / NBA Action", "As grandes lutas de boxe, MMA e especiais do basquete profissional americano."));
        }
        // 7. FILMES E SÉRIES
        else if (cat.contains("movie") || cat.contains("cinema") || cat.contains("filme") ||
                rawId.contains("telecine") || rawId.contains("hbo") || rawId.contains("warner") ||
                rawId.contains("megapix") || rawId.contains("tnt") || rawId.contains("space") ||
                rawId.contains("universal") || rawId.contains("cinemax")) {
            grid.add(new ScheduleItem("01:00", "06:00", "Madrugada de Cinema: Clássicos e Ação", "Sessão de cinema noturna com grandes sucessos, suspense e filmes premiados."));
            grid.add(new ScheduleItem("06:00", "09:30", "Sessão Matinal: Comédia e Aventura", "Filmes descontraídos para começar a manhã com energia e diversão."));
            grid.add(new ScheduleItem("09:30", "12:30", "Matinê Especial: Sucessos de Hollywood", "Grandes produções dos maiores estúdios mundiais em alta definição."));
            grid.add(new ScheduleItem("12:30", "15:30", "Sessão Família: Animação e Aventura", "Filmes aclamados para reunir toda a família com muita emoção."));
            grid.add(new ScheduleItem("15:30", "18:00", "Cine Ação: Ficção e Muita Adrenalina", "Efeitos visuais surpreendentes e perseguições eletrizantes."));
            grid.add(new ScheduleItem("18:00", "20:30", "Esquenta Prime: Blockbusters Mundiais", "O aquecimento do horário nobre com as maiores bilheterias do cinema."));
            grid.add(new ScheduleItem("20:30", "22:45", "Superestreia Prime Time: Cinema em 1080p FHD", "O filme principal da noite em alta definição com som de cinema."));
            grid.add(new ScheduleItem("22:45", "25:00", "Cine Night: Suspense, Ação e Terror", "Filmes intensos e envolventes para quem aprecia o melhor da sétima arte."));
        }
        // 8. INFANTIL
        else if (cat.contains("kid") || cat.contains("infantil") || cat.contains("desenho") ||
                rawId.contains("cartoon") || rawId.contains("gloob") || rawId.contains("nick") || rawId.contains("discoverykids")) {
            grid.add(new ScheduleItem("00:00", "06:00", "Clássicos Animados / Sono Tranquilo", "Desenhos calmos e historinhas divertidas para acompanhar a noite dos pequenos."));
            grid.add(new ScheduleItem("06:00", "12:00", "Manhã Divertida: Os Melhores Desenhos", "Aventuras mágicas, heróis e muitas risadas com os personagens mais queridos."));
            grid.add(new ScheduleItem("12:00", "18:00", "Tarde Animada: Maratonas e Desafios", "Episódios inéditos das séries de animação mais assistidas do mundo."));
            grid.add(new ScheduleItem("18:00", "21:00", "Cine Cartoon: Filmes e Longas Animados", "Sessão especial de cinema infantil com grandes aventuras em alta resolução."));
            grid.add(new ScheduleItem("21:00", "24:00", "Sessão Kids: Aventuras da Noite", "Histórias engraçadas e episódios especiais antes da hora de dormir."));
        }
        // 9. CANAIS 24 HORAS
        else if (cat.contains("24h") || rawId.contains("24h-") || chName.contains("24h")) {
            grid.add(new ScheduleItem("00:00", "24:00", (ch.name != null ? ch.name : "Canal") + " - Maratona 24 Horas", "Transmissão contínua e sem intervalos de " + (ch.name != null ? ch.name : "conteúdo") + ", 24 horas por dia em alta definição digital."));
        }
        // 10. GRADE INTELIGENTE POR CATEGORIA E HORÁRIO (Nunca deixa o canal sem EPG)
        else {
            if (cat.contains("esporte") || cat.contains("sport")) {
                grid.add(new ScheduleItem("00:00", "06:00", "Giro Esportivo da Madrugada", "Compactos e análises das competições esportivas do Brasil e do mundo."));
                grid.add(new ScheduleItem("06:00", "10:00", "Manhã Esportiva: Manchetes e Treinos", "Abertura da rodada com preparação dos atletas e dos clubes."));
                grid.add(new ScheduleItem("10:00", "13:00", "Debate dos Craques: Mesa Redonda", "Opiniões contundentes e debate dos lances mais marcantes do esporte."));
                grid.add(new ScheduleItem("13:00", "16:00", "Giro dos Campeonatos", "Cobertura especial dos principais campeonatos nacionais e internacionais."));
                grid.add(new ScheduleItem("16:00", "21:30", "Transmissão Esportiva Ao Vivo HD", "Transmissão digital oficial em tempo real com narração e comentários."));
                grid.add(new ScheduleItem("21:30", "24:00", "Pós-Jogo: Gols da Rodada e Melhores Momentos", "Análise aprofundada dos resultados do dia e classificação dos times."));
            } else if (cat.contains("noticia") || cat.contains("news") || cat.contains("jornal")) {
                grid.add(new ScheduleItem("00:00", "06:00", "Jornal da Madrugada 24H", "Cobertura contínua das principais notícias do Brasil e do mundo."));
                grid.add(new ScheduleItem("06:00", "10:00", "Manhã em Foco: Noticiário Ao Vivo", "Economia, política, mobilidade urbana e as primeiras informações do dia."));
                grid.add(new ScheduleItem("10:00", "14:00", "Edição do Meio-Dia: Plantão Nacional", "O resumo das notícias da manhã com análises dos comentaristas."));
                grid.add(new ScheduleItem("14:00", "18:00", "Tarde de Notícias: Cobertura em Tempo Real", "Apuração ágil e transmissões ao vivo direto das fontes de informação."));
                grid.add(new ScheduleItem("18:00", "21:30", "Jornal da Noite: O Balanço do Dia", "O balanço completo dos acontecimentos mais relevantes da jornada."));
                grid.add(new ScheduleItem("21:30", "24:00", "Painel Notícias: Análise e Opinião", "Especialistas debatem os rumos do país e do cenário internacional."));
            } else {
                grid.add(new ScheduleItem("00:00", "06:00", "Madrugada Especial", "Programação noturna com séries, documentários e grandes atrações."));
                grid.add(new ScheduleItem("06:00", "09:00", "Manhã Informativa e Variedades", "Dicas úteis, música, prestação de serviços e boas energias para começar o dia."));
                grid.add(new ScheduleItem("09:00", "12:00", "Revista Eletrônica Matinal", "Entretenimento, receitas, cultura e entrevistas exclusivas."));
                grid.add(new ScheduleItem("12:00", "14:00", "Edição da Tarde: Programação Especial", "Notícias da comunidade, esportes e variedades ao vivo."));
                grid.add(new ScheduleItem("14:00", "17:00", "Tarde de Sucessos e Séries", "Os programas e séries mais aclamados pelo público em alta definição."));
                grid.add(new ScheduleItem("17:00", "19:30", "Giro de Notícias e Entretenimento", "Os principais assuntos do dia comentados com leveza e dinamismo."));
                grid.add(new ScheduleItem("19:30", "21:00", "Horário Nobre: Grande Produção", "A atração principal do início da noite com alta qualidade digital."));
                grid.add(new ScheduleItem("21:00", "23:30", "Superatração da Noite", "Filmes, shows ou grandes episódios consagrados na televisão."));
                grid.add(new ScheduleItem("23:30", "24:00", "Encerramento e Destaques do Dia", "Os melhores momentos do dia e o que esperar da programação de amanhã."));
            }
        }

        ScheduleItem curItem = null;
        ScheduleItem nextItem = null;

        for (int i = 0; i < grid.size(); i++) {
            ScheduleItem item = grid.get(i);
            int sM = toM(item.s);
            int eM = toM(item.e);
            if (sM <= nowM && nowM < eM) {
                curItem = item;
                nextItem = grid.get((i + 1) % grid.size());
                break;
            } else if (eM > 1440 && (sM <= nowM || nowM < (eM - 1440))) {
                curItem = item;
                nextItem = grid.get((i + 1) % grid.size());
                break;
            }
        }

        if (curItem == null && !grid.isEmpty()) {
            curItem = grid.get(0);
            nextItem = grid.size() > 1 ? grid.get(1) : grid.get(0);
        }

        if (curItem == null) {
            String title = (ch.now != null && !ch.now.isEmpty()) ? ch.now : "Programação Ao Vivo";
            return new LiveSchedule(title, "Transmissão digital oficial em tempo real em alta definição.", "00:00", "24:00", 50, 30, "Programação Contínua", "24:00");
        }

        int sM = toM(curItem.s);
        int eM = toM(curItem.e);
        int dur = Math.max(15, eM - sM);
        int elapsed = Math.max(0, nowM - sM);
        int prog = Math.min(99, Math.max(2, Math.round(((float) elapsed / dur) * 100)));
        int rem = Math.max(1, dur - elapsed);

        String cleanStart = toH(sM);
        String cleanEnd = toH(eM);
        String nextStart = (nextItem != null) ? toH(toM(nextItem.s)) : cleanEnd;
        String nextTitle = (nextItem != null) ? nextItem.t : "Continuação da Programação";

        return new LiveSchedule(curItem.t, curItem.syn, cleanStart, cleanEnd, prog, rem, nextTitle, nextStart);
    }

    private static int toM(String str) {
        try {
            String[] p = str.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String toH(int m) {
        int norm = ((m % 1440) + 1440) % 1440;
        int h = norm / 60;
        int mn = norm % 60;
        return String.format("%02d:%02d", h, mn);
    }
}

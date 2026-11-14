"use strict";

const englishCopy = new Map(`
Tibop · Laboratoire de Scrabble|Tibop · Scrabble Research Studio
Tibop, vue d’ensemble|Tibop, overview
Tableau de bord local des simulations de Scrabble francophone.|Local dashboard for Scrabble research and simulations.
Aller au contenu|Skip to content
Navigation principale|Main navigation
Vue d’ensemble|Overview
Analyse|Analysis
Parties|Games
Administration|Administration
Profil de jeu|Game profile
Connexion…|Connecting…
Scrabble francophone|French Scrabble
Suivez les simulations et comparez les quatre niveaux de jeu.|Track simulations and compare the four playing levels.
Suivre un duel|Watch a duel
Campagne de simulation|Simulation campaign
Progression de la campagne|Campaign progress
Parties enregistrées|Recorded games
Indicateurs de simulation|Simulation metrics
Score moyen|Average score
Points par joueur|Points per player
Meilleur score|Highest score
Points en une partie|Points in one game
Durée moyenne|Average length
Tours par partie|Turns per game
Scrabbles|Bingos
Poses de sept lettres|Seven-tile plays
Elo des quatre bots|Elo of the four bots
Échelle relative à la campagne de référence|Rating scale for the reference campaign
Voir l’analyse|View analysis
En attente des premiers résultats.|Waiting for the first results.
Modèles du laboratoire|Lab models
Performance de jeu et analyse de position|Playing performance and position analysis
Expert entraîné|Trained Expert
Face à l’Expert de référence, sur des tirages réservés.|Against the reference Expert on held-out deals.
Score de match|Match score
Données JSON|JSON data
Llama local|Local Llama
Modèle local indisponible|Local model unavailable
NAB, recherche avancée|NAB, advanced search
Face à l’Expert entraîné.|Against the trained Expert.
Résultats des bots|Bot results
Duels sur des tirages réservés.|Matches on held-out deals.
Face à l’Expert de référence.|Against the reference Expert.
Analyse du moteur|Engine analysis
Réponse du modèle illisible.|The model returned an invalid response.
Le commentaire du modèle est indisponible.|Model commentary is unavailable.
Le commentaire du modèle a dépassé le délai de réponse.|Model commentary timed out.
La demande de commentaire n’a pas pu être déposée.|The commentary request could not be queued.
Ollama n’a renvoyé aucun commentaire.|Ollama returned no commentary.
Le service Ollama local est injoignable.|The local Ollama service cannot be reached.
Ollama n’a pas pu charger le modèle configuré.|Ollama could not load the configured model.
Commente les coups et leurs variantes.|Comments on moves and their alternatives.
Elo en jeu non mesuré.|Playing Elo has yet to be measured.
Commentaires de position|Position commentary
Une réponse exacte reprend les scores du moteur et choisit une variante à marge simulée maximale.|An exact response reproduces the engine scores and chooses a move with the highest simulated margin.
Comparatif des modèles d’analyse|Commentary model comparison
Modèle|Model
JSON valides|Valid JSON
Exactes|Exact
Médiane|Median
Analyse du jeu|Game analysis
Mesurez l’écart entre les niveaux et les habitudes de jeu.|Compare bot levels and playing habits.
Explorer les parties|Explore games
Indicateurs de stratégie|Strategy metrics
Victoires du premier joueur|First-player wins
Sur l’ensemble des parties|Across all games
Échanges|Exchanges
Part des tours|Share of turns
Passes|Passes
Performances par niveau|Performance by level
Classement, victoires et scores observés|Ratings, wins and recorded scores
Niveau|Level
Victoires|Wins
Nuls|Draws
Confrontations|Matchups
Victoires et nuls pour chaque paire de bots|Wins and draws for each bot pairing
En attente des premières confrontations.|Waiting for the first matchups.
Fréquence des mots|Word frequencies
Occurrences et points du coup|Occurrences and move scores
Historique des recherches|Research history
Campagnes, entraînements et évaluations par profil.|Campaigns, training and evaluations by profile.
En attente des premières études.|Waiting for the first studies.
Recherche dans le corpus|Search the corpus
Trouvez des positions, des archives et des analyses par thème.|Find positions, archives and analyses by topic.
Thème ou position|Topic or position
Ex. conserver un joker en milieu de partie|e.g. saving a blank in the middle game
Rechercher|Search
Ouvrez une partie pour reconstituer le plateau, coup par coup.|Open a game to replay the board move by move.
Exporter les parties|Export games
Origine des parties|Game source
Simulations|Simulations
Archives de compétition|Competition archives
Parties importées|Imported games
Duel en direct|Live duel
Historique des parties simulées|Simulation archive
Identifiant ou graine|Game ID or seed
Tous les niveaux|All levels
Découverte|Beginner
Initié|Intermediate
Stratège|Strategist
Expert|Expert
Filtrer|Filter
Partie|Game
Niveaux|Levels
Tours|Turns
Gagnant|Winner
Rejouer|Replay
Chargement des parties…|Loading games…
Précédent|Previous
Suivant|Next
Parties de championnat|Championship games
Chargement des archives…|Loading archives…
Finales récentes|Recent finals
Chargement des résultats…|Loading results…
Retrouvez les parties envoyées depuis l’application ou un fichier.|Browse games imported from the app or a file.
Importer un fichier|Import a file
Joueurs|Players
Chargement des parties importées…|Loading imported games…
Choisissez deux bots et suivez leur partie tour par tour.|Pick the adaptive bot or fixed levels and follow the duel move by move.
Mode de jeu|Mode
Bot adaptatif|Adaptive bot
Adaptatif|Adaptive
Niveaux fixes|Fixed levels
Elo cible du laboratoire|Target lab Elo
Premier joueur|First player
Second joueur|Second player
Graine facultative|Optional seed
Aléatoire|Random
Suivre le duel|Watch duel
Importez des parties, téléchargez les données et consultez l’état du service.|Import games, download the data and check the service.
Actualiser|Refresh
Exports|Exports
Téléchargez les parties déjà enregistrées.|Download recorded games.
Simulations et coups|Simulations and moves
Historique complet de la campagne.|Full campaign archive.
Exporter les simulations en JSON|Export simulations as JSON
Exporter les simulations en JSONL|Export simulations as JSONL
Résumé des parties|Game summaries
Une ligne par partie, pour un tableur.|One row per game for a spreadsheet.
Archive Expert × Expert|Expert vs Expert archive
Parties et coups des confrontations entre bots experts.|Games and moves from Expert matchups.
Exporter les parties expertes en JSON|Export Expert games as JSON
Exporter les parties expertes en JSONL|Export Expert games as JSONL
Relevés historiques avec leur provenance.|Historical records with source attribution.
Historique ajouté depuis un fichier.|Games added from a file.
Exporter les parties importées en JSON|Export imported games as JSON
Exporter les parties importées en JSONL|Export imported games as JSONL
Prototype Android|Android prototype
Le jeu et l’historique de vos parties.|Play and keep your game history.
Télécharger Tibop pour Android|Download Tibop for Android
Importer des parties|Import games
Ajoutez un export Tibop ou un fichier de parties.|Upload a Tibop export or a game file.
Choisir un fichier|Choose a file
Aucun fichier choisi|No file chosen
JSON ou JSONL · 32 Mio maximum|JSON or JSONL · 32 MiB maximum
↑ Importer|↑ Import
Voir les parties importées|View imported games
État de la campagne|Campaign status
En attente de la première mise à jour.|Waiting for the first update.
État|Status
Base de données|Database
Temps de calcul|Compute time
Lexique|Lexicon
Mots du lexique|Words in lexicon
Fermer|Close
Chargement de la partie…|Loading game…
Plateau du Scrabble|Scrabble board
Cases bonus|Premium squares
Mot triple|Triple word
Mot double|Double word
Lettre triple|Triple letter
Lettre double|Double letter
Coup précédent|Previous move
Choisir un coup|Select a move
Coup suivant|Next move
Lire la partie|Play replay
Coups joués|Moves
Analyse du coup|Move analysis
Analyser|Analyze
Sélectionnez un coup à examiner.|Select a move to examine.
Modèle d’analyse|Commentary model
Challenger le coup|Challenge move
En cours|Running
Terminée|Completed
En pause|Paused
En attente|Pending
Hors ligne|Offline
Service local|Local service
Service indisponible|Service unavailable
Le service local est momentanément indisponible.|The local service is temporarily unavailable.
Actualisé à |Updated at\x20
 duels de test| test games
Modèle local|Local model
 · Test de jeu| · Playing test
 Elo estimés| estimated Elo
 parties face à | games against\x20
l’Expert|Expert
 · Score de match | · Match score\x20
Intervalle à 95 % · |95% interval ·\x20
 à | to\x20
Sélection expérimentale · |Experimental move selection ·\x20
 coups choisis · | chosen moves ·\x20
 replis sur le moteur| engine fallbacks
Partie |Game\x20
Égalité|Draw
Joueur |Player\x20
Multijoueur local|Local multiplayer
Salon multijoueur|Multiplayer room
Fichier|File
Graine |Seed\x20
Rejouer la partie |Replay game\x20
 partie| game
 parties| games
 sur | of\x20
Aucune partie ne correspond à ces filtres.|No games match these filters.
Impossible de charger les parties. Réessayez avec le bouton Filtrer.|Could not load games. Try applying the filters again.
Les parties ajoutées dans Administration apparaîtront ici.|Games imported in Administration will appear here.
Impossible de charger les parties importées.|Could not load imported games.
Position de départ|Starting position
Coup refusé|Rejected move
 (refusé)| (rejected)
Passe|Pass
Échange|Exchange
Placement|Play
, variante |, alternative\x20
, après le coup |, after move\x20
 lettres| tiles
Coup joué|Played move
Maximum indiqué|Reported maximum
Écart|Gap
Rang du coup|Move rank
Chevalet avant le coup|Rack before the move
Placements légaux|Legal plays
Tirages simulés|Simulated draws
Chevalet vidé|Rack emptied
Reliquat |Rack leave\x20
Joué|Played
Écart estimé |Estimated margin\x20
Maximum signalé dans le relevé d’origine.|Maximum listed in the original record.
Valeurs publiées pour le lexique historique |Scores published for the historical lexicon\x20
Le relevé ne renseigne pas de maximum pour ce coup.|The record does not give a maximum for this move.
Recalculer|Recalculate
Sélectionnez une variante pour la placer sur le plateau.|Select an alternative to preview it on the board.
Comparez ce coup aux placements disponibles.|Compare this move to available plays.
Relancer l’analyse|Run analysis again
Analyse en cours avec |Analyzing with\x20
Aucun modèle d’analyse configuré.|No commentary model configured.
Le modèle est indisponible sur ce Mac.|The model is unavailable on this Mac.
Calcul des placements disponibles…|Calculating available plays…
Analyse indisponible pour ce coup. Réessayez.|Analysis unavailable for this move. Try again.
Coup |Move\x20
 · Variante | · Alternative\x20
Sac avant le coup · |Bag before move ·\x20
 jetons| tiles
Position · |Position ·\x20
Ajustements finaux · |Endgame adjustments ·\x20
Mettre en pause|Pause
Impossible de charger cette partie.|Could not load this game.
Document|Document
Ouvrir la position →|Open position →
Recherche en cours…|Searching…
 résultat| result
 résultats| results
Aucun résultat. Essayez un autre thème.|No results. Try another topic.
Recherche indisponible pour le moment.|Search is temporarily unavailable.
En direct · |Live ·\x20
Duel terminé · |Duel complete ·\x20
 gagne| wins
Préparation du duel…|Setting up the duel…
Diffusion interrompue.|The live stream was interrupted.
Impossible de démarrer le duel.|Could not start the duel.
Import en cours…|Importing…
 partie importée| game imported
 parties importées| games imported
Aucune nouvelle partie|No new games
 doublon ignoré| duplicate skipped
 doublons ignorés| duplicates skipped
Le fichier dépasse la limite de 32 Mio.|The file exceeds the 32 MiB limit.
Import impossible. Vérifiez le fichier et réessayez.|Import failed. Check the file and try again.
Aucune étude enregistrée pour ce profil.|No studies have been recorded for this profile.
Historique des recherches indisponible.|Research history unavailable.
Profil en préparation|Profile in preparation
Dynamique du duel|Match momentum
Score cumulé|Running score
Chances de victoire|Win probability
Probabilités estimées à chaque coup.|Estimated probabilities after each move.
Jetons piochés|Tiles drawn
Sac après le coup · |Bag after move ·\x20
Pousse|Sprout
Bois|Wood
Jade|Jade
Obsidienne|Obsidian
 victoires| wins
 nuls · | draws ·\x20
Télémétrie du laboratoire|Lab telemetry
Actualisation indisponible|Update unavailable
Mémoire|Memory
Batterie|Battery
En charge|Charging
Modèles Ollama|Ollama models
Requêtes Ollama|Ollama requests
Jetons en entrée|Input tokens
Jetons en sortie|Output tokens
Inconnu|Unknown
Disponible|Available
Indisponible|Unavailable
Maîtres historiques|Historical masters
Points de classement publiés, groupés par système de compétition.|Published rating snapshots grouped by competition system.
Échelle |Scale\x20
Sources officielles|Official sources
Relevés datés|Dated rating snapshots
Découvrir les maîtres|Meet the masters
Retracez le jeu en compétition, ses maîtres et ses moments marquants.|Explore the history, masters and milestones of competitive play.
Moments marquants|Milestones
Repères et anecdotes documentés.|Documented milestones and stories.
 tours| turns
Gagne|Wins
Trier par|Sort by
Récentes|Most recent
Elo le plus fort|Highest Elo
Score le plus élevé|Highest score
Parties les plus longues|Longest games
Parties les plus courtes|Shortest games
Carte des championnats|Championship map
Les lieux et les résultats des finales documentées.|Locations and results of documented finals.
Parcourir les finales|Browse finals
Écart de points|Point difference
Archives indisponibles pour le moment.|Archives are temporarily unavailable.
Elo relatif aux adversaires de la campagne de référence.|Elo relative to opponents in the reference campaign.
Score de chaque joueur après chaque tour. Cliquez ou utilisez les flèches pour parcourir la partie.|Each player's score after every turn. Click or use the arrow keys to browse the game.
Estimation du moteur après chaque coup. Cliquez ou utilisez les flèches pour parcourir la partie.|Engine estimate after each move. Click or use the arrow keys to browse the game.
Jetons effectivement reçus après ce tour.|Tiles actually drawn after this turn.
Afficher cette variante sur le plateau à la place du coup joué.|Preview this alternative on the board in place of the played move.
Cote publiée par la compétition indiquée. Les autres systèmes utilisent leur propre échelle.|Rating published by the stated competition. Other rating systems use separate scales.
Progression visuelle sur l’échelle Elo du laboratoire.|Visual progression on the lab Elo scale.
Chance de piocher un joker · |Chance of drawing a blank ·\x20
Projection issue des duels de référence à niveau égal, ajustée par l’écart Elo et les jetons restants. Une nulle vaut une demi-victoire.|Estimate calibrated on equal-level reference games, adjusted for the Elo gap and remaining tiles. A draw counts as half a win.
Projection indicative par écart de points, Elo et nombre de jetons restants.|Indicative estimate based on the score gap, Elo and remaining tiles.
Jetons piochés reconstruits depuis le chevalet suivant. La chance d’un joker avant le coup est calculée sur les jetons inconnus.|Drawn tiles are reconstructed from the next rack. The pre-move chance of drawing a blank uses unknown tiles.
Mots d’ouverture|Opening words
Cases bonus utilisées|Premium-square usage
Jetons posés et score moyen des coups concernés.|Tiles placed and average score for those moves.
Carte des placements|Placement heatmap
Explorez les cases avec la souris ou les flèches du clavier.|Explore squares with the mouse or arrow keys.
Mesure du plateau|Board measure
Jetons posés|Tiles placed
Contenu des échanges|Tile exchanges
Score individuel le plus élevé|Highest individual score
Score total le plus élevé|Highest combined score
 poses| plays
 poses · | plays ·\x20
 jeton| tile
 jetons posés| tiles placed
 ouverts| opened
 ouvertures sur | openings in\x20
 parties indexées.| indexed games.
pts à l’ouverture|pts on the opening move
Aucune ouverture indexée.|No opening moves have been indexed.
pts en moyenne|average pts
Avec bonus|With a premium
Sans bonus|Without a premium
 échanges · | exchanges ·\x20
 échanges| exchanges
 jetons rendus.| tiles returned.
jetons rendus|tiles returned
Aucun échange indexé.|No exchanges have been indexed.
Case simple|Standard square
pts en moyenne sur ces coups|average pts for those moves
Chargement des statistiques détaillées…|Loading detailed statistics…
Ce profil attend encore son index statistique.|Detailed statistics for this profile are pending.
Les statistiques détaillées sont momentanément indisponibles.|Detailed statistics are temporarily unavailable.
sur |of\x20
Parties de la campagne. Les nulles comptent dans le total.|Games in the campaign. Draws count in the total.
Une pose de sept jetons sur l’ensemble des poses, hors passes et échanges.|A seven-tile play divided by all placements, excluding passes and exchanges.
Un échange compte pour un tour, quel que soit le nombre de jetons rendus.|An exchange counts as one turn regardless of how many tiles were returned.
Une passe ne pose aucun jeton et ne change pas le chevalet.|A pass places no tiles and leaves the rack unchanged.
Score moyen du coup entier lorsqu’un jeton est posé sur ce bonus. Un coup peut utiliser plusieurs catégories.|Average score of the whole move when a tile lands on this premium. A move may cover multiple premium types.
Victoires par côté|Win rate by side
Les deux côtés et les nulles sont comptés sur le même ensemble de duels.|Both sides and draws use the same set of games.
Moyenne des scores finaux par joueur. Ouvrez le détail par niveau.|Mean final score per player. Open the breakdown by level.
Plus haut score individuel d’une partie. Ouvrez le duel correspondant.|Highest individual score in a game. Open that duel.
Nombre total de tours divisé par les parties. Ouvrez les duels classés par longueur.|Total turns divided by games. Open duels ordered by length.
Nombre de coups posant sept jetons. Ouvrez les comptes et leur dénominateur.|Number of seven-tile plays. Open the counts and denominator.
Coup simulé à cet Elo|Simulated move at this Elo
Voir le coup|Preview move
Choix du moteur à cet Elo, sur ce même chevalet.|Engine choice at this Elo, using the same rack.
Calcul de la variante…|Calculating the alternative…
Variante indisponible pour cet Elo.|No alternative available at this Elo.
NAB · bot adaptatif|NAB · adaptive bot
Elo des niveaux de référence|Reference-level Elo
Étudier les coups|Study the moves
Étudier les coups de |Study the moves of\x20
Meilleur coup publié|Best published play
Jouer entre amis|Play with friends
Jouer au Scrabble|Play Scrabble
Tibop Studio · Comprendre le jeu|Tibop Studio · Explore the game
Accueil de Tibop Studio|Tibop Studio home
Rejouez des parties de Scrabble, comparez les stratégies et explorez les archives de compétition.|Replay Scrabble games, compare strategies and explore competition archives.
Langue des parties|Game language
Les parties à la loupe|A closer look at the game
Rejouez les meilleurs coups et découvrez ce qui fait la différence sur le plateau.|Replay the best moves and discover what makes a difference on the board.
Voir un duel|Watch a duel
Parties simulées|Simulated games
La force des joueurs|Playing strength
Le classement Elo compare les résultats des quatre niveaux de jeu.|Elo ratings compare the results of the four playing levels.
Voir le classement|See the rankings
Le bot Expert à l’essai|Putting the Expert bot to the test
Comparez la version entraînée au bot Expert de départ.|Compare the trained Expert bot with the original version.
Les deux versions jouent sur des tirages gardés pour le test.|Both versions play on deals reserved for testing.
Les résultats du bot Expert sont disponibles dans le profil français.|Expert bot results are available for the French profile.
Tests des modèles d’analyse|Commentary model tests
Le test vérifie les scores annoncés et le choix du meilleur coup selon la simulation.|The test checks the reported scores and whether the model picks the best move in the simulation.
Format valide|Valid format
Réponses exactes|Accurate answers
Temps médian|Median time
Temps p95|95th-percentile time
95 % des réponses arrivent avant ce délai.|95% of responses arrive within this time.
Carnet de recherche|Research notebook
Simulations, entraînements et résultats des tests.|Simulations, training and test results.
Découvrir les champions|Meet the champions
Stratégie|Strategy
Comparez les joueurs, leurs mots préférés et les cases qu’ils utilisent.|Compare players, their favourite words and the squares they use.
Qui gagne face à qui ?|Who beats whom?
Résultats des duels entre niveaux, parties nulles comprises.|Match results between playing levels, including draws.
Mots les plus joués|Most played words
Nombre d’apparitions et score moyen des coups.|Frequency and average move score.
Les cases bonus|Bonus squares
Combien de fois servent-elles et combien rapportent les coups qui les utilisent ?|How often are they used and how much do those moves score?
Où joue-t-on ?|Where do players place tiles?
Sélectionnez une case pour voir ses chiffres.|Select a square to see its stats.
Lettres posées|Tiles placed
Lettres échangées|Exchanged tiles
Le classement en détail|Rankings in detail
Scores, victoires et classement Elo par niveau.|Scores, wins and Elo by playing level.
Rechercher une idée de jeu|Search for a playing idea
Trouvez des exemples dans les parties, les archives et les analyses.|Find examples in games, archives and analyses.
Parties à rejouer|Games to replay
Choisissez une partie et avancez à votre rythme, un coup à la fois.|Choose a game and go at your own pace, one move at a time.
Numéro de partie ou de tirage|Game number or deal seed
Entre deux experts|Between two Experts
Meilleurs classements|Highest rankings
Meilleurs scores individuels|Highest individual scores
Meilleurs scores cumulés|Highest combined scores
Réglez le niveau des deux joueurs et regardez-les jouer.|Set both playing levels and watch them play.
NAB, niveau réglable|NAB, adjustable level
Choisir un niveau fixe|Choose a fixed level
Niveau de NAB (Elo)|NAB level (Elo)
Lancer le duel|Start the duel
Reproduire un tirage|Repeat a deal
Numéro du tirage|Deal seed
Championnats|Championships
Remontez le fil des finales et découvrez les joueurs qui ont marqué la compétition.|Explore past finals and meet the players who shaped the competition.
Les grands joueurs|Great players
Suivez leur classement au fil des ans, selon les règles de chaque compétition.|Follow their ratings over the years in each competition's ranking system.
Les finales sur la carte|Finals on the map
Choisissez un lieu ou parcourez les années pour retrouver les résultats.|Choose a location or browse the years to see the results.
Outils|Tools
Retrouvez vos fichiers de parties et téléchargez Tibop pour Android.|Find your game files and download Tibop for Android.
Télécharger des parties|Download games
Utilisez le format CSV pour ouvrir les résultats dans un tableur.|Use CSV to open results in a spreadsheet.
Version 0.17.0|Version 0.17.0
Collection|Collection
Combinez six plateaux, quatre chevalets et six styles de tuiles. L’aperçu réunit vos choix avant de jouer.|Mix six boards, four racks and six tile styles. Preview your combination before playing.
Lexique personnel|Personal dictionary
Ajoutez vos mots au lexique de jeu pour l’entraînement hors ligne, ou importez une liste complète.|Add your words to the game dictionary for offline practice, or import a full word list.
Circuit bonus|Bonus Circuit
Trois nouveaux défis à enchaîner hors ligne avec un ticket. La première entrée est offerte.|One ticket gives you three new offline puzzles. Your first entry is free.
Tibop pour Android|Tibop for Android
L’atelier de NAB|NAB’s Workshop
Jouez douze niveaux en un ou deux coups et agrandissez l’atelier en réussissant les finales contre NAB.|Play twelve levels in one or two moves and expand the workshop by completing the finales against NAB.
Définitions hors ligne|Offline definitions
Consultez les mots du coup joué sans connexion. Les fiches françaises relient aussi les verbes conjugués à leur infinitif et les noms au pluriel à leur singulier.|Look up played words offline. French entries also link inflected verbs to their infinitive and plural nouns to their singular form.
Deux coups d’avance|Two Moves Ahead
Douze défis hors ligne par langue. Jouez deux mots avec le même chevalet, puis regardez le rejeu de NAB.|Twelve offline puzzles in each language. Play two words from one rack, then watch NAB’s replay.
Duels et tournoi en ligne|Online duels and tournament
Ouvrez le lien de la bêta Android pour rejoindre vos amis. Activez les notifications dans Amis pour être prévenu quand vous pouvez jouer.|Open the Android beta link to join your friends. Enable notifications in Friends to know when you can play.
Terminez les trois défis d’un week-end pour débloquer ce fond de carte à partager. Retrouvez-le dans votre collection.|Finish all three challenges on a weekend to unlock this share-card background. Find it in your collection.
Aperçu de Nocturne dorée, une carte bleu nuit avec des tuiles dorées|Preview of Nocturne dorée, a midnight-blue card with golden tiles
APK Android|Android APK
Version de test à installer sur un appareil Android.|Test version for Android devices.
Télécharger l’APK|Download APK
État de la simulation et du service|Simulation and service status
État du service|Service status
Comment la partie évolue|How the game develops
Chances de victoire estimées|Estimated win chances
Demander un commentaire|Request commentary
Les parties ajoutées dans Outils apparaîtront ici.|Games imported in Tools will appear here.
Points Elo|Elo points
Occurrences|Occurrences
Départ|Start
Coup|Turn
Cotes publiées|Published ratings
Tous les joueurs|All players
Points par coup|Points per move
Case sélectionnée|Selected square
Aucune pose|No tiles placed
Plus fréquente|More frequent
Moins fréquente|Less frequent
Coup moins élevé|Lower-scoring move
Coup plus élevé|Higher-scoring move
Finale précédente|Previous final
Finale suivante|Next final
Vainqueur|Winner
Finaliste|Runner-up
Match nul|Draw
`.trim().split("\n").map((line) => line.split("|")));

let uiLocale = "fr";
const pageCopy = [];
const originalTitle = document.title;
const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
while (walker.nextNode()) {
  const textNode = walker.currentNode;
  const text = textNode.data.trim();
  if (englishCopy.has(text)) pageCopy.push([textNode, "data", textNode.data, text]);
}
for (const element of document.querySelectorAll("[aria-label], [placeholder], [title], img[alt], meta[name=description]")) {
  for (const attr of ["aria-label", "placeholder", "title", "alt", "content"]) {
    const value = element.getAttribute(attr);
    if (value && englishCopy.has(value)) pageCopy.push([element, attr, value, value]);
  }
}

function t(text) {
  return uiLocale === "en" ? englishCopy.get(text) ?? text : text;
}

function setLanguage(locale) {
  uiLocale = locale;
  document.documentElement.lang = locale;
  document.title = t(originalTitle);
  for (const [element, property, original, text] of pageCopy) {
    const localized = locale === "en" && property === "data" ? element.parentElement?.dataset.english ?? t(text) : t(text);
    const value = original.replace(text, localized);
    if (property === "data") element.data = value;
    else element.setAttribute(property, value);
  }
}

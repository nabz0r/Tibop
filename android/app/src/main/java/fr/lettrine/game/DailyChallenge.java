package fr.lettrine.game;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

record DailyChallenge(LocalDate date, PracticePosition position, String openingWord,
                      int openingStart, int openingEnd) {
    // Each row contains an opening word, a one-tile continuation, and a longer continuation.
    // Catalog order is part of the calendar. Keep this version's date assignments stable.
    private static final String[] CATALOG = """
            ABRI ABRIS ABRICOT
            AIRE AIRES AFFAIRE
            ALLER ALLERS EMBALLER
            AMI AMIS AMIRAL
            AMOUR AMOURS AMOUREUX
            ANGE ANGES ORANGE
            ARC ARCS ARCANE
            BALLE BALLES EMBALLE
            BAR BARS BARQUE
            BEC BECS BECQUEE
            BIJOU BIJOUX BIJOUTIER
            BLE BLES SABLE
            BOIS BOISE DEBOISE
            BOITE BOITES EMBOITE
            BOL BOLS BOLIDE
            BOND BONDS BONDON
            BORD BORDS DEBORDE
            BOUCHE BOUCHES DEBOUCHE
            BRAS BRASE EMBRASE
            BULLE BULLES BULLETIN
            CABLE CABLES RECABLE
            CAGE CAGES ENCAGE
            CAILLOU CAILLOUX CAILLOUTE
            CAR CARS CARTON
            CASE CASES CASERNE
            CERF CERFS CERFEUIL
            CHAMP CHAMPS CHAMPION
            CHANT CHANTS ENCHANTE
            CHEF CHEFS CHEFFERIE
            CHIEN CHIENS CHIENNE
            CLOCHE CLOCHES CLOCHETTE
            COEUR COEURS ECOEURE
            COL COLS COLLINE
            COMPTE COMPTES DECOMPTE
            CONTE CONTES RACONTER
            COQ COQS COQUILLE
            CORDE CORDES ACCORDE
            CORNE CORNES LICORNE
            COTE COTES COTEAU
            COU COUS COURAGE
            COUP COUPS DECOUPE
            COUR COURS PARCOURIR
            CREPE CREPES CREPERIE
            DENT DENTS DENTELLE
            FACE FACES SURFACE
            FAR FARS FARINE
            FARD FARDS DEFARDE
            FEE FEES FEERIE
            FER FERS ENFERME
            FETE FETES FETERA
            FEU FEUX FEUILLES
            FIL FILS DEFILER
            FLAMME FLAMMES ENFLAMME
            FLEUR FLEURS EFFLEURE
            FLOT FLOTS FLOTTE
            FOI FOIS FOIRE
            FOND FONDS REFONDE
            FOUR FOURS FOURMI
            GANT GANTS ELEGANTE
            GEL GELS DEGELER
            GLACE GLACES DEGLACE
            GRAVE GRAVES AGGRAVE
            HANCHE HANCHES DEHANCHE
            HOMME HOMMES BONHOMME
            ILE ILES FILET
            JARDIN JARDINS JARDINER
            JOUR JOURS AJOURNE
            LAC LACS LACET
            LAIT LAITS LAITERIE
            LAME LAMES LAMELLE
            LANCE LANCES RELANCE
            LIEU LIEUX MILIEUX
            LIME LIMES LIMETTE
            LION LIONS LIONNE
            LIT LITS LITERIE
            LOUP LOUPS LOUPER
            LUNE LUNES LUNETTE
            MAIN MAINS DEMAIN
            MARC MARCS MARCHE
            MARRON MARRONS MARRONNE
            MATE MATES TOMATES
            MATIN MATINS MATINEE
            MER MERS MERCI
            MIE MIES MIETTE
            MIEL MIELS MIELLEUX
            MINE MINES MINEUR
            MIRE MIRES ADMIRER
            MONT MONTS REMONTE
            MOT MOTS MOTEUR
            MOULE MOULES MOULEUR
            MUR MURS MURMURE
            NEIGE NEIGES ENNEIGE
            NID NIDS NIDIFIE
            NOIR NOIRS NOIRCEUR
            NOTE NOTES ANNOTER
            NUAGE NUAGES NUAGEUX
            OR ORS ORANGE
            ORGE ORGES ORGEAT
            ORAGE ORAGES ORAGEUX
            ORNE ORNES ORNEMENT
            OUTIL OUTILS OUTILLER
            PARC PARCS PARCELLE
            PART PARTS PARTAGE
            PASSE PASSES REPASSE
            PATE PATES PATELIN
            PECHE PECHES PECHEUR
            PIE PIES PAPIER
            PIERRE PIERRES EMPIERRE
            PILE PILES EMPILE
            PIN PINS PINCE
            PISTE PISTES DEPISTE
            PLAT PLATS PLATEAU
            PLIE PLIES REPLIER
            PLUME PLUMES DEPLUME
            POCHE POCHES EMPOCHE
            POINT POINTS POINTER
            POLI POLIS POLITESSE
            POMME POMMES POMMETTE
            PONT PONTS PONTER
            POSE POSES REPOSER
            PRE PRES APRES
            PRUNE PRUNES PRUNEAU
            PUCE PUCES PUCERON
            QUART QUARTS QUARTIER
            RAME RAMES RAMEAU
            RAT RATS RATON
            RAYON RAYONS RAYONNE
            REVE REVES REVEIL
            RIME RIMES PRIMER
            ROBE ROBES DEROBE
            ROC ROCS ROCHE
            ROSE ROSES ROSERAIE
            ROUTE ROUTES DEROUTE
            SABLE SABLES ENSABLE
            SAC SACS SACOCHE
            SEL SELS SELLE
            SENS SENSE SENSIBLE
            SIEGE SIEGES ASSIEGE
            SIR SIRE SIRENE
            SOIR SOIRS SOIREE
            SOL SOLS SOLEIL
            SON SONS SONNET
            SOU SOUS SOUPE
            TABLE TABLES TABLEAU
            TACHE TACHES ATTACHE
            TARTE TARTES TARTELETTE
            TASSE TASSES ENTASSE
            TEMPE TEMPES TEMPETE
            TERRE TERRES ENTERRER
            TETE TETES ENTETE
            TIGRE TIGRES TIGRESSE
            TOUR TOURS RETOUR
            TRAIN TRAINS ENTRAINE
            TRICOT TRICOTS TRICOTER
            TROU TROUS TROUVE
            TUILE TUILES TUILERIE
            VAL VALS VALISE
            VELU VELUS CHEVELU
            VENT VENTS VENTRE
            VER VERS VERSANT
            VERRE VERRES VERRERIE
            VIN VINS VINAIGRE
            VOILE VOILES DEVOILE
            VOL VOLS VOLCAN
            """.split("\n");
    private static final String[] ENGLISH_CATALOG = """
            ACT ACTS ACTION
            AIR AIRS AIRPORT
            ART ARTS ARTIST
            BAG BAGS BAGGAGE
            BAND BANDS BANDAGE
            BAR BARS BARKER
            BATH BATHS BATHROOM
            BEAR BEARS BEARING
            BLOCK BLOCKS BLOCKAGE
            BOARD BOARDS CUPBOARD
            BONE BONES WISHBONE
            BOOT BOOTS BOOTLACE
            BRANCH BRANCHY BRANCHES
            BRIDGE BRIDGES FOOTBRIDGE
            BURN BURNS BURNER
            CAKE CAKES PANCAKE
            CAN CANS CANDLE
            CAR CARS CARPET
            CART CARTS CARTON
            CAT CATS CATFISH
            CHEST CHESTS CHESTNUT
            CLEAN CLEANS CLEANER
            CLOWN CLOWNS CLOWNING
            COIN COINS COINAGE
            COOK COOKS COOKING
            CORN CORNS POPCORN
            COT COTS COTTAGE
            CRACK CRACKS CRACKER
            CROW CROWS SCARECROW
            CUT CUTS CUTTER
            DATE DATES UPDATE
            DEEP DEEPS DEEPER
            DESK DESKS DESKTOP
            DOG DOGS HOTDOG
            DRAW DRAWS DRAWING
            DRINK DRINKS DRINKER
            DRUM DRUMS DRUMMER
            DUST DUSTS DUSTER
            EAST EASTS EASTERN
            FAIR FAIRS FAIRWAY
            FAST FASTS FASTEN
            FIELD FIELDS FIELDER
            FIRE FIRES CAMPFIRE
            FLAG FLAGS FLAGPOLE
            FOAM FOAMS FOAMING
            FORK FORKS FORKLIFT
            FROST FROSTS FROSTING
            GATE GATES GATEWAY
            GIFT GIFTS GIFTED
            GLOW GLOWS GLOWING
            GRAPE GRAPES GRAPEFRUIT
            GREEN GREENS EVERGREEN
            GUEST GUESTS GUESTROOM
            HANG HANGS HANGER
            HAWK HAWKS HAWKING
            HELM HELMS HELMET
            HOME HOMES HOMEWORK
            HOOK HOOKS HOOKED
            HOUR HOURS HOURGLASS
            HURT HURTS HURTFUL
            INK INKS INKING
            ITEM ITEMS ITEMIZE
            JUMP JUMPS JUMPER
            KEY KEYS KEYBOARD
            KNIT KNITS KNITTING
            LAND LANDS ISLANDER
            LATE LATER LATEST
            LEAK LEAKS LEAKAGE
            LEMON LEMONS LEMONADE
            LION LIONS LIONESS
            LOAD LOADS UPLOAD
            LONE LONER LONELY
            LOOP LOOPS LOOPHOLE
            MAIN MAINS MAINLAND
            MAP MAPS ROADMAP
            MASK MASKS MASKING
            MEAL MEALS OATMEAL
            MELT MELTS MELTING
            MIND MINDS REMINDER
            MIX MIXT MIXERS
            MOTH MOTHS MOTHER
            MOUSE MOUSES MOUSETRAP
            MUSE MUSES MUSEUM
            NAME NAMES NAMESAKE
            NEED NEEDS NEEDFUL
            NET NETS NETWORK
            NORTH NORTHS NORTHERN
            NUT NUTS WALNUT
            OIL OILS OILCAN
            OUT OUTS OUTLINE
            PAGE PAGES PAGEANT
            PAINT PAINTS PAINTER
            PARK PARKS PARKLAND
            PATH PATHS FOOTPATH
            PEEL PEELS PEELER
            PET PETS PETTING
            PIPE PIPES PIPELINE
            PLAN PLANS PLANET
            PLAY PLAYS PLAYGROUND
            PLUM PLUMS PLUMBER
            POND PONDS PONDER
            POP POPS POPCORN
            POT POTS POTHOLDER
            RAIL RAILS RAILWAY
            RAMP RAMPS RAMPAGE
            RATE RATES PRORATE
            READ READS READER
            RING RINGS EARRING
            ROAD ROADS RAILROAD
            ROCK ROCKS ROCKET
            ROOF ROOFS ROOFTOP
            ROSE ROSES ROSEBUD
            ROW ROWS ROWING
            RUN RUNS RUNNER
            SACK SACKS SACKING
            SALT SALTS SALTED
            SCORE SCORES SCOREBOARD
            SEAM SEAMS SEAMLESS
            SEED SEEDS SEEDLING
            SHARK SHARKS SHARKING
            SHIP SHIPS SHIPWRECK
            SHORE SHORES SHORELINE
            SIGN SIGNS SIGNAL
            SKI SKIS SKIING
            SLEEP SLEEPS SLEEPER
            SNAIL SNAILS SNAILED
            SOAP SOAPS SOAPBOX
            SOIL SOILS TOPSOIL
            SORT SORTS SORTER
            SPACE SPACES SPACESHIP
            SPOT SPOTS SPOTLIGHT
            STAR STARS STARFISH
            STEAM STEAMS STEAMER
            STEP STEPS STEPPING
            STONE STONES GEMSTONE
            STRAW STRAWS STRAWBERRY
            STREET STREETS STREETCAR
            SUGAR SUGARS SUGARCANE
            SWIM SWIMS SWIMMER
            TALK TALKS TALKER
            TANK TANKS TANKER
            TASK TASKS MULTITASK
            TICK TICKS TICKET
            TIME TIMES TIMELINE
            TIP TIPS TIPTOE
            TOAST TOASTS TOASTER
            TOP TOPS TABLETOP
            TRACK TRACKS TRACKER
            TRAP TRAPS TRAPPING
            TUNE TUNES TUNEFUL
            TWIN TWINS TWINNING
            UNIT UNITS UNITED
            VEST VESTS INVESTOR
            VISIT VISITS VISITOR
            WALL WALLS WALLPAPER
            WALK WALKS SIDEWALK
            WAVE WAVES WAVEFORM
            WELL WELLS WELLNESS
            WHEEL WHEELS PINWHEEL
            WING WINGS WINGSPAN
            WISE WISER WISEST
            WORD WORDS PASSWORD
            YARD YARDS BACKYARD
            YELL YELLS YELLING
            """.split("\n");
    private static final String ENGLISH_DRAW = "AAAAAAAABBCCDDDDEEEEEEEEEEEEFFGGGHHIIIIIIIJKLLLLMMNNNNNNOOOOOOOOPPQRRRRRRSSSSTTTTTTUUUUVVWWXYYZ";
    private static final String DRAW = "AAAAABBCCDDDEEEEEEEEFGGHIIIIJLLLMMNNNNOOOOPQRRRSSSTTTTUUUUVXYZ";
    private static final int CALENDAR_STEP = 73; // Coprime with the size of this catalog.

    static DailyChallenge forDate(LocalDate date, Lexicon lexicon) {
        String[] catalog = lexicon.language == GameLanguage.ENGLISH ? ENGLISH_CATALOG : CATALOG;
        long day = date.toEpochDay();
        int index = (int) Math.floorMod(day * CALENDAR_STEP, (long) catalog.length);
        for (int tried = 0; tried < catalog.length; tried++) {
            String[] words = catalog[(index + tried) % catalog.length].split(" ");
            if (!lexicon.contains(words[0]) || !lexicon.contains(words[1]) || !lexicon.contains(words[2])) {
                continue;
            }
            DailyChallenge challenge = prepare(date, words[0], words[1], words[2], day, lexicon);
            if (challenge != null) return challenge;
        }
        return null;
    }

    private static DailyChallenge prepare(LocalDate date, String opening, String easy, String reference, long day,
                                          Lexicon lexicon) {
        Random deal = new Random(day ^ 0x4c65747472696e65L);
        int direction = (day & 1) == 0 ? 1 : FrenchGame.SIZE;
        int firstOffset = deal.nextInt(opening.length());
        List<Character> rack = new ArrayList<>(FrenchGame.RACK_SIZE);
        for (char letter : extension(opening, reference).toCharArray()) rack.add(letter);
        char easyLetter = extension(opening, easy).charAt(0);
        if (!rack.contains(easyLetter)) rack.add(easyLetter);
        int[] used = new int[26];
        for (char letter : opening.toCharArray()) used[letter - 'A']++;
        for (char letter : rack) used[letter - 'A']++;
        String draw = lexicon.language == GameLanguage.ENGLISH ? ENGLISH_DRAW : DRAW;
        while (rack.size() < FrenchGame.RACK_SIZE) {
            char letter = draw.charAt(deal.nextInt(draw.length()));
            if (used[letter - 'A'] >= lexicon.language.tileCount(letter)) continue;
            rack.add(letter);
            used[letter - 'A']++;
        }
        for (int i = rack.size() - 1; i > 0; i--) Collections.swap(rack, i, deal.nextInt(i + 1));

        for (int i = 0; i < opening.length(); i++) {
            int offset = (firstOffset + i) % opening.length();
            int start = FrenchGame.CENTER - offset * direction;
            List<FrenchGame.Tile> referenceTiles = place(opening, reference, start, direction, rack);
            if (referenceTiles.isEmpty()) continue;
            List<FrenchGame.Tile> easyTiles = place(opening, easy, start, direction, rack);
            if (easyTiles.isEmpty()) continue;
            char[] board = new char[FrenchGame.CELLS];
            boolean[] blanks = new boolean[FrenchGame.CELLS];
            for (int letter = 0; letter < opening.length(); letter++) {
                board[start + letter * direction] = opening.charAt(letter);
            }
            FrenchGame.Preview target = FrenchGame.preview(board, blanks, rack, lexicon, referenceTiles);
            FrenchGame.Preview warmup = FrenchGame.preview(board, blanks, rack, lexicon, easyTiles);
            if (target.valid() && warmup.valid() && target.score() > warmup.score()) {
                PracticePosition position = new PracticePosition(board, blanks, rack,
                        new MatchReview.Alternative(target, referenceTiles), null);
                return new DailyChallenge(date, position, opening, start, start + (opening.length() - 1) * direction);
            }
        }
        return null;
    }

    private static String extension(String opening, String word) {
        int offset = word.indexOf(opening);
        return word.substring(0, offset) + word.substring(offset + opening.length());
    }

    private static List<FrenchGame.Tile> place(String opening, String word, int start, int direction,
                                               List<Character> rack) {
        int prefix = word.indexOf(opening);
        int head = FrenchGame.step(start, direction, -prefix);
        if (head < 0 || FrenchGame.step(head, direction, word.length() - 1) < 0) return List.of();
        List<Character> remaining = new ArrayList<>(rack);
        List<FrenchGame.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < word.length(); i++) {
            if (i >= prefix && i < prefix + opening.length()) continue;
            char letter = word.charAt(i);
            int rackIndex = remaining.indexOf(letter);
            remaining.set(rackIndex, '\0');
            tiles.add(new FrenchGame.Tile(head + i * direction, rackIndex, letter, false));
        }
        return List.copyOf(tiles);
    }
}

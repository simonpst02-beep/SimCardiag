# SimCarDiag (Android)

Application Android (Kotlin + Jetpack Compose) pour lire et analyser les données OBD-II d'une voiture avec un boîtier **OBDLink MX+** (ou tout ELM327 en Bluetooth classique).

## Fonctionnalités

**Connexion**
- Appairage guidé pour l'OBDLink MX+, progression de la connexion en clair
- Détection de la puce STN (OBDLink) : timing adaptatif rapide
- Lecture groupée jusqu'à 6 capteurs par requête sur les voitures CAN (repli automatique sinon)
- Reconnexion automatique après une coupure (jusqu'à 5 min) et au lancement de l'appli
- Service au premier plan : la lecture et l'enregistrement continuent écran éteint, notification avec vitesse et température

**Accueil**
- Carte « État du véhicule » : tout va bien / à surveiller / attention, avec la marche à suivre
- Tableau de bord personnalisable : modèles (Essentiel, Éco, Sport, Diagnostic, Tout afficher), widgets valeur, jauge ou courbe pour chaque mesure, bilan du trajet, éco-conduite, codes défaut, enregistrement ; déplacer, agrandir, retirer
- Phrases d'aide sous les valeurs (autonomie, moteur froid, alternateur…)
- Mode conduite : 4 très grandes valeurs au choix, alertes en bandeau
- Alertes sonores et vibrations (surchauffe, alternateur, batterie, régime, réserve, entretien)

**Diagnostic**
- Codes défaut confirmés et en attente, avec gravité et « Que faire ? »
- Contexte du défaut (freeze frame) : vitesse, régime, température… au moment de l'apparition
- Préparation au contrôle technique (moniteurs antipollution)
- Lecture du VIN : marque et année (indicative), reconnaissance automatique du véhicule
- Effacement des codes avec avertissement
- **Par calculateur** : recherche rapide (calculateurs OBD : moteur, boîte…) ou analyse approfondie (adresses CAN 0x240–0x25F et 0x600–0x7F7, « Tester Present »), puis test de chaque calculateur séparément : codes défaut (OBD, UDS 19 02 ou KWP2000 18), nom du calculateur, temps de réponse, effacement propre à ce calculateur

**Trajets**
- Chaque trajet est enregistré automatiquement : distance, durée, consommation, vitesse max, note d'éco-conduite
- Historique sur 8 semaines (km et consommation)
- Détail CSV en option (format Excel FR), partageable

**Garage**
- Plusieurs véhicules (tableau de bord, trajets et entretien séparés)
- Carnet d'entretien avec rappels au kilomètre et/ou à la date
- Données constructeur : ajout de mesures personnalisées (commande, en-tête, formule A/B/C/D)
- Réglages : thème clair/sombre, seuils d'alerte, son, vibration, reconnexion

**Version 2.0**
- **Réseaux supplémentaires** dans l'analyse des calculateurs : adresses longues 29 bits (adressage normalisé 18 DA xx F1, utile pour Volvo et d'autres marques) et réseau confort sur les broches 3-11 (OBDLink : protocole STN 53, vitesse réglée à 95,2 kbit/s pour Opel)
- **Filtre à particules** : pression et température standard (PID 7A / 7C) si la voiture les fournit, détection de régénération, mesures constructeur ajoutées par l'utilisateur
- **Import de mesures** au format CSV de Torque Pro (fonction `signed()` prise en charge)
- **Test batterie et démarreur** : tension au repos, minimum pendant le démarrage, charge de l'alternateur
- **Rapport PDF** de diagnostic, partageable
- **Carnet de carburant** : consommation réelle entre pleins complets, coût aux 100 km, dépenses par mois
- **Carte des trajets** (OpenStreetMap, GPS du téléphone) colorée selon la consommation, freinages brusques
- **Revoir un trajet** : courbes superposées et curseur synchronisé avec la carte
- **Surveillance des défauts** en roulant (relecture chaque minute)
- **Widget** d'écran d'accueil : état, autonomie, prochain entretien
- **Chronos** 0-100, 80-120 et 400 m automatiques
- **Icône**, version 2.0, signature de la version publiable, **sauvegarde / restauration** des données

**Tablette** : en paysage ou sur grand écran (≥ 700 dp), navigation latérale et mise en page en colonnes.

## Compiler et installer
1. Installez **Android Studio** (version récente, JDK 17 inclus).
2. *File → Open* et choisissez le dossier `SimCarDiag`. Android Studio télécharge Gradle et les dépendances.
3. Sur le téléphone ou la tablette : activez les *Options développeur* et le *Débogage USB*, branchez l'appareil.
4. Cliquez sur ▶ *Run*.

## Utilisation
1. Branchez l'OBDLink MX+ sur la prise OBD et mettez le contact.
2. Appuyez sur le bouton **Connect** du boîtier, puis appairez « OBDLink MX+ » dans les réglages Bluetooth d'Android dans les 2 minutes.
3. Ouvrez l'appli et choisissez le boîtier. Les fois suivantes, la connexion est automatique.

## Structure
```
app/src/main/java/com/example/obdanalyzer/
├── ObdApp.kt / MainActivity.kt   démarrage, autorisations
├── obd/      connexion Bluetooth, PIDs, décodage, VIN, formules, CSV
├── core/     ObdManager (session, reconnexion, trajets), modèles, éco-score, alertes
├── data/     sauvegarde locale (réglages, véhicules, trajets)
├── service/  service au premier plan + notification
└── ui/       écrans Compose (accueil, diagnostic, trajets, garage, mode conduite)
```

## Limites
- Consommation : utilise le débit carburant (PID 5E) si la voiture le fournit, sinon une estimation à partir du débit d'air (moins fiable en diesel).
- L'année tirée du VIN est indicative : beaucoup de constructeurs européens ne la codent pas.
- Les mesures constructeur dépendent de votre modèle : il faut trouver la bonne commande et la bonne formule.
- Kilométrage : l'OBD standard ne donne pas le compteur ; saisissez-le une fois dans le Garage, il est ensuite mis à jour à chaque trajet.

## Version publiable (Play Store ou installation directe)
1. Dans Android Studio : *Build › Generate Signed Bundle / APK* pour créer une clé (`.jks`) la première fois.
2. Créez `keystore.properties` à la racine du projet (ne le partagez pas) :
   ```
   storeFile=ma-cle.jks
   storePassword=…
   keyAlias=obd
   keyPassword=…
   ```
3. `Build › Build Bundle(s) / APK(s)` en variante *release* : l'APK signé s'installe sur n'importe quel téléphone, l'AAB s'envoie sur la Play Console.
4. L'identifiant de l'appli est `fr.simcardiag.app` : il doit être unique sur le Play Store, changez-le si besoin avant la première publication.

## Limites (suite)
- Les calculateurs non OBD répondent parfois dans un langage propre à la marque (GMLAN chez Opel, protocole Volvo) : l'appli les signale mais ne peut pas lire leurs codes.
- Le réseau confort nécessite un boîtier OBDLink (puce STN).
- Les mesures du filtre à particules propres à chaque modèle sont à importer ou saisir.

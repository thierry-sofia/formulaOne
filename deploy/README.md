# Installation sur le VPS : formulaOne remplace ScentSync

formulaOne reprend l'installation existante de ScentSync : même service (`scentsync.service`),
même jar (`/home/ubuntu/scentsync.jar`), même fichier d'environnement (`/home/ubuntu/scentsync.env`),
même port interne (`127.0.0.1:9091`, `FORMULAONE_PORT=9091`). **Nginx, le domaine et le certificat ne changent pas** :
nginx écoute sur le port 8091 en HTTPS (fichier `explore.aquilegia.pro`) et envoie vers le 9091.
Adresse : `https://explore.aquilegia.pro:8091`.
La base SQL Server `maBase` est sur le même VPS (`localhost:1433`).

1. **Utilisateur SQL en lecture seule** sur `maBase` (SELECT sur `dbo.FORMULE` et `dbo.FORMULEDET`).
2. **Sauvegarde de l'ancien fichier** puis remplacement :
   `cp /home/ubuntu/scentsync.env /home/ubuntu/scentsync.env.ancien`, puis écrire dans
   `/home/ubuntu/scentsync.env` (`chmod 600`) le contenu de `deploy/scentsync.env.example` rempli
   (les variables de l'ancien ScentSync ne servent plus).
3. **Service** : `deploy/scentsync.service` remplace l'unité existante
   (`sudo cp deploy/scentsync.service /etc/systemd/system/ && sudo systemctl daemon-reload`).
   Vérifier avant que le `ExecStart` de l'ancienne unité n'avait pas d'options à garder.
4. **Workflow** : GitHub n'a pas laissé l'outil de Claude créer un fichier de workflow. Ajouter
   `.github/workflows/deploy.yml` avec le contenu de `deploy/deploy.yml.workflow`, et les secrets
   `VPS_SSH_KEY`, `VPS_HOST`, `VPS_USER`, `VPS_PORT` (mêmes valeurs que les autres dépôts).
5. **Dépôt ScentSync** : désactiver son déploiement automatique (`deploy.yml`, push sur `master`),
   sinon un push y remettrait l'ancien ScentSync à la place de formulaOne.
6. **Déployer** : Actions > Deploy > Run workflow (ou push sur `main`).

Vérifier : `curl -I http://127.0.0.1:9091/login.html` (200 = l'application répond ; `curl -I http://127.0.0.1:9091/api/me` doit répondre 401),
`sudo journalctl -u scentsync.service -n 50`.

Retour arrière : `sudo cp /home/ubuntu/scentsync.jar.bak /home/ubuntu/scentsync.jar && sudo systemctl restart scentsync.service`
(l'ancien ScentSync n'est restauré qu'avec son ancien fichier `scentsync.env.ancien`).

## Connexion, sessions et sécurité

- **Page de connexion** `login.html` et bouton **Se déconnecter** (en haut de chaque page). Plus de fenêtre
  d'authentification du navigateur : l'authentification HTTP Basic est supprimée (elle aurait permis d'essayer des mots de passe sans limite).
- **Session** : cookie `JSESSIONID` `HttpOnly`, `Secure`, `SameSite=Strict`. **30 minutes d'inactivité**, et 12 heures au
  maximum quoi qu'il arrive. Le redémarrage du service ferme toutes les sessions.
- **Session coupée aussitôt** quand le compte est supprimé, que son mot de passe change ou que son rôle change.
- **Frein aux essais** : 10 échecs depuis une même adresse IP, ou 20 sur un même identifiant, en 15 minutes =
  blocage de 15 minutes (même avec le bon mot de passe). Les échecs sont journalisés (`[auth]`) sans mot de passe.
- Message d'erreur identique pour un identifiant inconnu et un mauvais mot de passe.
- En-têtes : CSP stricte, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `nosniff`. Les requêtes qui
  modifient exigent un en-tête de contrôle (protection contre les requêtes venant d'un autre site).
- **Le cookie `Secure` exige HTTPS** : se connecter par `https://explore.aquilegia.pro:8091`, pas en `http://`.

**Adresse IP réelle (nginx)** : le frein aux essais par adresse IP lit `X-Forwarded-For`. Le bloc nginx du port 8091 doit contenir
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` et `proxy_set_header X-Forwarded-Proto $scheme;`.
Sans eux, tous les visiteurs auraient l'adresse 127.0.0.1 et 10 échecs de n'importe qui bloqueraient tout le monde 15 minutes.
Vérifier : `sudo journalctl -u scentsync.service | grep "\[auth\]"` doit montrer des `ip=` différents de `127.0.0.1`.

## Numéros CAS dans l'écran de détail et l'export CSV

Le CAS n'est pas dans `maBase` : formulaOne le lit dans un **fichier d'extraction du Kardex**,
`/home/ubuntu/formulaone-cas.tsv` (autre chemin : variable `FORMULAONE_CAS_FILE`).

- **Format** : une ligne par matière, colonnes séparées par des **tabulations** (ou des `;`), comme dans le Kardex :
  `code ; libellé ; type ; CAS ; coût ; naturelle`. Le code est la 1re colonne, le CAS la 4e. Une 1re ligne d'en-tête
  contenant une colonne « CAS » est acceptée (c'est alors cette colonne qui est lue).
- **Mise en place** : coller la liste du Kardex dans le fichier (`nano /home/ubuntu/formulaone-cas.tsv`). Le fichier est
  **relu tout seul** quand il change, sans redémarrage. Les journaux disent `[cas] N matières lues`.
- **Résultat** : colonne **CAS** dans le détail d'une formule et dans le CSV (entre « Matière » et « Poids »). Vide quand
  la matière n'a pas de CAS (`.`, `mixture` ou vide dans le Kardex) ou n'est pas dans le fichier.
- **Solutions** (`…10DP`, `…1CDP`, présentes dans le fichier sans CAS) : CAS de la matière de base (6 premiers caractères du code).
- **Limite** : le rapprochement se fait sur le code matière du Kardex. Les formules d'une autre source qui utilisent
  d'autres codes n'auront pas de CAS tant qu'il n'y a pas de correspondance de codes.

## Comptes utilisateurs (login / mot de passe)

**Écran d'administration** : `https://explore.aquilegia.pro:8091/admin-comptes.html` (lien « Comptes » à côté du
titre, visible des administrateurs seulement). On y crée un compte, change un mot de passe ou un rôle, supprime un compte.
Chaque modification est écrite tout de suite dans `/home/ubuntu/formulaone-users.properties` et prise en compte
sans redémarrage.

- **Rôles** : *Administrateur* (gère les comptes) ou *Utilisateur* (lecture des formules).
- **Compte du serveur** : `FORMULAONE_USER` / `FORMULAONE_PASSWORD` de `scentsync.env` reste administrateur. Il apparaît
  dans la liste mais ne se modifie que dans `scentsync.env`. Il garantit qu'il y a toujours un administrateur.
- **Règles** : login de 3 à 50 caractères (lettres, chiffres, `. _ @ -`), mot de passe de 10 caractères au moins,
  pas de doublon (majuscules ignorées), on ne supprime pas son propre compte, on ne retire pas le dernier administrateur.
- **Sources autorisées** : pour chaque utilisateur, « Toutes les sources » (par défaut, y compris pour les comptes
  existants) ou une liste de sources. La restriction est appliquée **côté serveur** : liste, recherche, filtre
  par source, détail d'une formule et export CSV (une formule d'une source interdite répond « introuvable »).
  Un administrateur voit toujours tout. Le bouton « Sources » de chaque ligne modifie le choix, à effet immédiat.
- Mots de passe stockés uniquement sous forme d'empreinte BCrypt (jamais en clair, jamais dans les journaux).
- Le dossier `/home/ubuntu` doit être inscriptible par l'utilisateur du service (c'est le cas : `User=ubuntu`).

**Sans l'écran (dépannage)** : le fichier est lisible, une ligne par compte `login=empreinte;ROLE;SOURCES` (`ROLE` = `ADMIN`
ou `USER`, `USER` si absent ; `SOURCES` = `*` pour toutes, ou les sources séparées par des virgules, chacune encodée
comme dans une URL, `*` si absent). Une empreinte : `java -jar /home/ubuntu/scentsync.jar hash`. Redémarrer le service après
une modification à la main. Une ligne invalide empêche l'application de démarrer
(`sudo journalctl -u scentsync.service -n 30`). Autre chemin : variable `FORMULAONE_USERS_FILE`.

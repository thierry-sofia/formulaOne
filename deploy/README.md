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

Vérifier : `curl -I http://127.0.0.1:9091` (401 = l'application répond et demande le mot de passe),
`sudo journalctl -u scentsync.service -n 50`.

Retour arrière : `sudo cp /home/ubuntu/scentsync.jar.bak /home/ubuntu/scentsync.jar && sudo systemctl restart scentsync.service`
(l'ancien ScentSync n'est restauré qu'avec son ancien fichier `scentsync.env.ancien`).

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

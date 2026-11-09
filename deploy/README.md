# Installation sur le VPS : formulaOne remplace ScentSync

formulaOne reprend l'installation existante de ScentSync : même service (`scentsync.service`),
même jar (`/home/ubuntu/scentsync.jar`), même fichier d'environnement (`/home/ubuntu/scentsync.env`),
même port (`127.0.0.1:8091`). **Nginx, le domaine et le certificat ne changent pas.**
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

Vérifier : `curl -I http://127.0.0.1:8091` (401 = l'application répond et demande le mot de passe),
`sudo journalctl -u scentsync.service -n 50`.

Retour arrière : `sudo cp /home/ubuntu/scentsync.jar.bak /home/ubuntu/scentsync.jar && sudo systemctl restart scentsync.service`
(l'ancien ScentSync n'est restauré qu'avec son ancien fichier `scentsync.env.ancien`).

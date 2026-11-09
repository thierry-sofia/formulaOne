# Installation sur le VPS (une seule fois)

formulaOne tourne sur `127.0.0.1:8095`, derrière nginx en HTTPS. La base SQL Server `maBase`
est sur le même VPS (`localhost:1433`).

1. **Utilisateur SQL en lecture seule** sur `maBase` (SELECT sur `dbo.FORMULE` et `dbo.FORMULEDET`).
2. **Fichier d'environnement** :
   `cp deploy/formulaone.env.example /home/ubuntu/formulaone.env && chmod 600 /home/ubuntu/formulaone.env`,
   puis renseigner les 3 valeurs obligatoires.
3. **Service** :
   `sudo cp deploy/formulaone.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable formulaone.service`
   (il démarrera au premier déploiement).
4. **Nginx + HTTPS** : adapter `deploy/nginx-formulaone.conf` (nom de domaine), le placer dans
   `/etc/nginx/sites-available/formulaone`, l'activer, `sudo certbot --nginx -d <domaine>`, `sudo nginx -t && sudo systemctl reload nginx`.
5. **Secrets GitHub** du dépôt : `VPS_SSH_KEY`, `VPS_HOST`, `VPS_USER`, `VPS_PORT` (mêmes valeurs que les autres dépôts).
6. **Workflow** : GitHub n'a pas laissé l'outil de Claude créer un fichier de workflow. Sur GitHub, ajouter
   `.github/workflows/deploy.yml` avec le contenu de `deploy/deploy.yml.workflow` (Add file > Create new file).
7. **Déployer** : Actions > Deploy > Run workflow (ou push sur `main`).

Vérifier : `curl -I http://127.0.0.1:8095` (401 = l'application répond et demande le mot de passe),
`sudo journalctl -u formulaone.service -n 50`.

Retour arrière : `sudo cp /home/ubuntu/formulaone.jar.bak /home/ubuntu/formulaone.jar && sudo systemctl restart formulaone.service`.

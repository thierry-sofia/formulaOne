# formulaOne

Application web de consultation et de recherche des formules de la base SQL Server `maBase`.
Lecture seule : aucune donnée n'est modifiée.

## Fonctionnalités

- **Recherche** par libellé ou code de formule (résultats paginés, 25 par page, recherche au fil de la saisie).
- **Détail** d'une formule : en-tête (source, poids total, prix) et composition (matières et poids).
- Interface en une seule page HTML, thème clair ou sombre automatique.

## Technologies

- Java 17, Spring Boot (web, JDBC, security)
- SQL Server (pilote `mssql-jdbc`)
- Maven

## Prérequis

- JDK 17
- Maven
- Accès à la base `maBase`, avec les tables `dbo.FORMULE` et `dbo.FORMULEDET`

## Configuration

Les identifiants ne sont jamais dans le code : ils viennent de variables d'environnement.

| Variable | Rôle | Défaut |
|---|---|---|
| `FORMULAONE_DB_URL` | URL JDBC de la base | `jdbc:sqlserver://localhost:1433;databaseName=maBase;encrypt=true;trustServerCertificate=true` |
| `FORMULAONE_DB_USER` | Utilisateur de la base | aucun (obligatoire) |
| `FORMULAONE_DB_PASSWORD` | Mot de passe de la base | aucun (obligatoire) |
| `FORMULAONE_USER` | Utilisateur de l'interface | `admin` |
| `FORMULAONE_PASSWORD` | Mot de passe de l'interface | aucun (obligatoire) |

L'application refuse de démarrer si une variable obligatoire manque.

## Lancer l'application

```bash
export FORMULAONE_DB_USER=...
export FORMULAONE_DB_PASSWORD=...
export FORMULAONE_PASSWORD=...
mvn spring-boot:run
```

Ou, après `mvn package` :

```bash
java -jar target/formulaone-*.jar
```

L'application écoute uniquement sur `127.0.0.1:8095`. Pour l'exposer, passez par un reverse proxy
(nginx) en **HTTPS** : l'authentification HTTP Basic fait circuler le mot de passe en clair sinon.

## API

Toutes les routes demandent une authentification HTTP Basic.

- `GET /api/formules?q=<texte>&page=<n>&size=<n>` : recherche paginée (`size` limité à 100, `q` à 100 caractères).
- `GET /api/formules/{key}` : détail d'une formule et de ses lignes (404 si introuvable).

## Sécurité

- Requêtes SQL paramétrées, caractères spéciaux de `LIKE` neutralisés.
- Affichage via `textContent` (pas d'injection HTML).
- CSRF désactivé car l'application est en lecture seule (GET uniquement).

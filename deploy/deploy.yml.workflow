name: Deploy

# Construit le jar et le deploie sur formulaone.service (127.0.0.1:8095) a chaque
# push sur main, ou a la demande (Actions > Deploy > Run workflow).
# Meme principe que le deploiement de ParfumIQ / ScentSync.
#
# Secrets requis (Settings > Secrets and variables > Actions), memes valeurs
# que sur les autres depots, meme VPS :
#   VPS_SSH_KEY, VPS_HOST, VPS_USER, VPS_PORT
#
# Prerequis cote VPS (une seule fois) : voir deploy/README.md
# (formulaone.env, formulaone.service, nginx).
#
# Le jar precedent est conserve (/home/ubuntu/formulaone.jar.bak) pour un
# retour arriere rapide :
#   sudo cp /home/ubuntu/formulaone.jar.bak /home/ubuntu/formulaone.jar
#   sudo systemctl restart formulaone.service

on:
  push:
    branches: [ main ]
  workflow_dispatch: {}

jobs:
  deploy:
    name: Build & deploy sur le VPS
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Cache Maven dependencies
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: |
            ${{ runner.os }}-maven-

      - name: Build jar
        run: mvn -q -B -ntp package

      - name: Copy jar to VPS
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.VPS_HOST }}
          username: ${{ secrets.VPS_USER }}
          key: ${{ secrets.VPS_SSH_KEY }}
          port: ${{ secrets.VPS_PORT }}
          source: "target/formulaone.jar"
          target: "/home/ubuntu/formulaone-upload"
          strip_components: 1

      - name: Restart formulaone service
        uses: appleboy/ssh-action@v1.2.0
        with:
          host: ${{ secrets.VPS_HOST }}
          username: ${{ secrets.VPS_USER }}
          key: ${{ secrets.VPS_SSH_KEY }}
          port: ${{ secrets.VPS_PORT }}
          script: |
            set -e
            [ -f /home/ubuntu/formulaone.jar ] && cp /home/ubuntu/formulaone.jar /home/ubuntu/formulaone.jar.bak
            mv /home/ubuntu/formulaone-upload/formulaone.jar /home/ubuntu/formulaone.jar
            rmdir /home/ubuntu/formulaone-upload
            sudo systemctl restart formulaone.service
            sleep 5
            sudo systemctl is-active formulaone.service

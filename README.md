# Bisq monitoring checker

# Docker deployment

* check out this repository on the target server
* obtain the Slack channel webhook on which you want to post monitoring messages
* Start monitoring: ```SLACK_URL="http://YOUR_SECRET_URL docker-compose up -d```
* inspect output via ```docker-compose logs -f```

# Testing without Docker

This is not really recommended, there's a lot of things need, and for instance the netcat dependency behaves
differently between mac and linux, so te preconfigured docker container is a safer bet. If on Linux, the Dockerfile 
could be used as a guide on how to install everything. 

* tor is installed
* curl is installed
* netcat is installed
* pipenv is installed
* python 2.7 is installed

Run:
```
pipenv install -r requirements.txt
```

Do this if you're on mac:

```
cp /usr/bin/nc /usr/local/bin/
```

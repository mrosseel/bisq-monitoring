###
# The directory of the Dockerfile should contain your 'hostname' and 'private_key' files.
# In the docker-compose.yml file you can pass the ONION_ADDRESS referenced below.
###

# pull base image
FROM openjdk:8u181-jdk-stretch

RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    vim \
    fakeroot \
    sudo \
    tor \
    torsocks \
    build-essential \
    netcat && rm -rf /var/lib/apt/lists/*

RUN git clone https://github.com/mrosseel/bisq-monitoring.git
WORKDIR /bisq-monitoring/
#RUN git checkout Development
# workaround for https://stackoverflow.com/questions/53010200/maven-surefire-could-not-find-forkedbooter-class
RUN mvn clean install -DforkCount=0

COPY start_tor.sh ./
RUN  chmod +x *.sh
WORKDIR /bisq-monitoring/

CMD ./start_tor.sh && java -cp ./target/bisq-monitoring*.jar io.bisq.monitoring.Monitoring --useSlack true --slackPriceSecret ${SLACK_PRICE_URL}  --slackSeedSecret ${SLACK_SEED_URL}  --slackBTCSecret ${SLACK_BTC_URL}
#CMD tail -f /dev/null

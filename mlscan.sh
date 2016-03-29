#!/bin/sh

# An example of mlscan command-line usage

senders="\
test1@example.com \
test1@example.com"

excludedDomain="example.com"

java -jar target/mlscan-0.1-SNAPSHOT.jar -c 0.2 -f data/*.txt -s $senders -e "$excludedDomain" -m multinomial_bayes -t data/test/*.txt -o /tmp/

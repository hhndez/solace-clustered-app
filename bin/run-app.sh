#!/bin/bash

if [ "$#" -ne 6 ]; then
	echo ""
	echo "USAGE: $0 <solace-ip> <appname> <instance#> <vpn> <user> <pass>"
	echo ""
	exit
fi
host=$1
app=$2
inst=$3
vpn=$4
user=$5
pass=$6

cd `dirname $0`/..

export LD_LIBRARY_PATH=../solclient/lib:../solclientj/lib:$LD_LIBRARY_PATH

classpath="../solclientj/lib/solclientj-7.1.2.136.jar:target/clustered-app-1.0-SNAPSHOT.jar"
java -cp $classpath -Djava.library.path=../solclientj/lib \
	com.solacesystems.poc.App $host $app $inst $vpn $user $pass


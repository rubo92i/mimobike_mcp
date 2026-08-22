#!/bin/bash
SERVICE_NAME=mcp
JAVA=/usr/lib/jvm/java-21/bin/java
JAR=/opt/services/mcp/mcp.jar
CONFIG=/opt/services/mcp/application.properties
JAVA_OPTS="-Xms256m -Xmx1g"
PID_PATH_NAME=/tmp/mcp-pid

# NOTE: additional-location (not config.location) — the repository allowlist
# lives in the application.yml inside the jar; the external file only adds
# secrets/overrides (tokens, port, logging).
start_service() {
  nohup "$JAVA" $JAVA_OPTS -jar "$JAR" --spring.config.additional-location="$CONFIG" >>/dev/null 2>&1 &
  echo $! > $PID_PATH_NAME
  echo "$SERVICE_NAME started ..."
}

stop_service() {
  PID=$(cat $PID_PATH_NAME)
  echo "$SERVICE_NAME stopping ..."
  kill $PID
  echo "$SERVICE_NAME stopped ..."
  rm $PID_PATH_NAME
}

case $1 in
start)
  echo "Starting $SERVICE_NAME ..."
  if [ ! -f $PID_PATH_NAME ]; then
    start_service
  else
    echo "$SERVICE_NAME is already running ..."
  fi
  ;;
stop)
  if [ -f $PID_PATH_NAME ]; then
    stop_service
  else
    echo "$SERVICE_NAME is not running ..."
  fi
  ;;
restart)
  if [ -f $PID_PATH_NAME ]; then
    stop_service
    echo "$SERVICE_NAME starting ..."
    start_service
  else
    echo "$SERVICE_NAME is not running ..."
  fi
  ;;
*)
  echo "Usage: $0 {start|stop|restart}"
  ;;
esac

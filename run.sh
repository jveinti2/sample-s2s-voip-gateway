#!/bin/bash
set -e

# Load environment variables from .env file if it exists
if [ -f .env ]; then
    echo "Loading environment variables from .env..."
    source .env
fi

mvn compile exec:java -Dexec.mainClass=com.example.s2s.voipgateway.NovaSonicVoipGateway


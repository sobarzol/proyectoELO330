#!/bin/bash
# Script para ejecutar el cliente Go con configuración de audio para PipeWire

# Configurar variables de entorno para mejor compatibilidad de audio
export PULSE_LATENCY_MSEC=30
export AUDIODEV=default

# Suprimir mensajes de ALSA (opcional - descomenta si quieres)
# export ALSA_CONFIG_PATH=/dev/null

# Ejecutar el cliente
./client "$@"

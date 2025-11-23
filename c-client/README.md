# Cliente de Chat gRPC en C

Cliente de chat implementado en C con soporte de audio.

## Características

- ✅ UI moderna con emojis
- ✅ Validación de nombres duplicados
- ✅ Mensajería en tiempo real
- ✅ Soporte de audio (comandos /mic y /listen)
- ✅ Multi-threading

## Requisitos

- gcc
- pthread
- portaudio (para funcionalidad de audio)

## Instalación de Dependencias

### Arch Linux
```bash
sudo pacman -S portaudio
```

### Ubuntu/Debian
```bash
sudo apt-get install portaudio19-dev
```

### macOS
```bash
brew install portaudio
```

## Compilar

```bash
cd c-client
make
```

## Ejecutar

```bash
./client
```

## Comandos Disponibles

- `/quit`, `/exit`, `/disconnect` - Salir del chat
- `/mic on` - Activar micrófono
- `/mic off` - Desactivar micrófono
- `/listen on` - Activar altavoces
- `/listen off` - Desactivar altavoces

## Notas

El cliente C tiene una implementación simplificada de gRPC para demostración.
Para una implementación completa de gRPC, se requeriría integrar grpc-c.

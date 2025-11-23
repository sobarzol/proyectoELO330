# Guía de Instalación de Herramientas

Esta guía te ayudará a instalar todas las herramientas necesarias para el proyecto.

## Verificar Herramientas Instaladas

Primero, ejecuta este comando para ver qué herramientas te faltan:

```bash
make check-tools
```

## Instalación de Herramientas

### 1. Protocol Buffers Compiler (protoc)

#### Arch Linux
```bash
sudo pacman -S protobuf
```

#### Ubuntu/Debian
```bash
sudo apt-get install -y protobuf-compiler
```

#### macOS
```bash
brew install protobuf
```

### 2. Go Plugins para protoc

Una vez tengas Go instalado, ejecuta:

```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
```

Asegúrate de que `$(go env GOPATH)/bin` esté en tu PATH:

```bash
export PATH="$PATH:$(go env GOPATH)/bin"
```

Agrega esta línea a tu `~/.bashrc` o `~/.zshrc` para hacerlo permanente.

### 3. Poetry (Python)

#### Instalación recomendada
```bash
curl -sSL https://install.python-poetry.org | python3 -
```

#### Arch Linux
```bash
sudo pacman -S python-poetry
```

#### pip (alternativa)
```bash
pip install --user poetry
```

Agrega Poetry a tu PATH (si usaste el script de instalación):
```bash
export PATH="$HOME/.local/bin:$PATH"
```

### 4. Rust y Cargo

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

Luego, recarga tu shell o ejecuta:
```bash
source "$HOME/.cargo/env"
```

### 5. Maven (Java)

#### Arch Linux
```bash
sudo pacman -S maven
```

#### Ubuntu/Debian
```bash
sudo apt-get install -y maven
```

#### macOS
```bash
brew install maven
```

## Después de Instalar Todo

1. **Recarga tu shell** para que reconozca las nuevas herramientas:
   ```bash
   source ~/.bashrc  # o ~/.zshrc si usas zsh
   ```

2. **Verifica de nuevo** que todo esté instalado:
   ```bash
   make check-tools
   ```

3. **Instala las dependencias del proyecto**:
   ```bash
   make install-deps
   ```

## Solución de Problemas

### protoc-gen-go no se encuentra

Si después de instalar los plugins de Go sigues teniendo problemas, verifica que el PATH esté correcto:

```bash
echo $PATH | grep $(go env GOPATH)/bin
```

Si no aparece nada, agrega esto a tu `~/.bashrc` o `~/.zshrc`:

```bash
export PATH="$PATH:$(go env GOPATH)/bin"
```

### Poetry no se encuentra

Si instalaste Poetry con el script oficial, agrega esto a tu `~/.bashrc` o `~/.zshrc`:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

### Los colores no se ven en el Makefile

Si estás usando un shell diferente a bash, asegúrate de que soporte códigos ANSI. La mayoría de shells modernos lo soportan, pero puedes probar:

```bash
echo -e "\033[0;32mEsto debería verse verde\033[0m"
```

Si no ves el texto en verde, tu terminal puede necesitar configuración adicional.

## Resumen de Comandos Rápidos

```bash
# Arch Linux - Instalar todo de una vez
sudo pacman -S protobuf python-poetry maven rust

# Plugins de Go
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# Configurar PATH
export PATH="$PATH:$(go env GOPATH)/bin:$HOME/.local/bin"

# Verificar
make check-tools

# Instalar dependencias del proyecto
make install-deps
```

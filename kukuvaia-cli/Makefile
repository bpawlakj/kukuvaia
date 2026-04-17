.PHONY: build test generate clean run

VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")

build: generate
	go build -ldflags "-X main.version=$(VERSION)" -o kukuvaia ./cmd/kukuvaia/

test:
	go test ./...

generate:
	go generate ./...

clean:
	rm -f kukuvaia

run: build
	./kukuvaia

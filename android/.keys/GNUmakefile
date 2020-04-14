KEYSTORE = keystore.pkcs12
PASSWORD_FILE = keystore.pwd

PRIVATE_KEY = release-key.sec
PUBLIC_KEY = release-key.pub
SIGNING_KEY = $(PRIVATE_KEY)

all: $(KEYSTORE) $(PUBLIC_KEY)

$(KEYSTORE): AudioJournal.crt $(PASSWORD_FILE)
	openssl pkcs12 -inkey $(PRIVATE_KEY) -in "$<" -export -nodes \
		-passout "file:$(PASSWORD_FILE)" -out "$@"

%.pwd:
	tr -dc 'a-zA-Z0-9' < /dev/random | head -c32 > "$@"

%.sec:
	openssl genrsa -out "$@" 4096

%.pub: %.sec
	openssl rsa -in "$<" -outform PEM -pubout -out "$@"

%.csr: $(PRIVATE_KEY)
	openssl req -new -key "$<" -out "$@" \
		-sha256 -subj "/CN=AudioJournal"

%.crt: %.csr
	openssl req -x509 -days $$((15*365)) -key "$(SIGNING_KEY)" \
		-nodes -out "$@" -outform PEM -in "$<"

.PHONY: all

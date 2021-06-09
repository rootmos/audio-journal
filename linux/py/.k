go() {
    make -C ../trec build LOG_LEVEL=${1-INFO} && \
        TREC=$(readlink -f ../trec/build/trec) \
        ./audio-journal --log=${1-INFO} record
}

toggle() {
    while sleep 2; do
        pactl set-source-mute alsa_input.usb-Burr-Brown_from_TI_USB_Audio_CODEC-00.analog-stereo toggle
    done
}

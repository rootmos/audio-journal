LOG_LEVEL=${LOG_LEVEL-INFO}
go() {
    make -C ../trec build LOG_LEVEL=$LOG_LEVEL && \
        TREC=$(readlink -f ../trec/build/trec) \
        ./audio-journal --log=$LOG_LEVEL "$@"
}

toggle() {
    while sleep 2; do
        pactl set-source-mute alsa_input.usb-Burr-Brown_from_TI_USB_Audio_CODEC-00.analog-stereo toggle
    done
}

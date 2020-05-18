restart-adb-as-root() {
    adb kill-server
    sudo adb start-server
    adb devices
}

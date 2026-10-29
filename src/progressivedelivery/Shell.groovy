package progressivedelivery

class Shell {

    static String quote(Object value) {
        return "'" + String.valueOf(value).replace("'", "'\\''") + "'"
    }
}

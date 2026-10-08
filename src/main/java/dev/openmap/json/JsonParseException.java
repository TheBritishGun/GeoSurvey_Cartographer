package dev.openmap.json;

// Thrown when this parser does not accept a document.
public class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonParseException(String message) {
        super(message);
    }

    public JsonParseException(String message, Throwable cause) {
        super(message, cause);
    }

    JsonParseException(String message, boolean writableStackTrace) {
        super(message, null, true, writableStackTrace);
    }
}

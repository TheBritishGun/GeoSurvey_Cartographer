package dev.openmap.json;

// Thrown when a document is not JSON this parser accepts.
// Unchecked. Every catch in this tree is written against IOException or this class.
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

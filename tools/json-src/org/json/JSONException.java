package org.json;

/** Test-harness stand-in for Android's org.json. Behaviourally compatible. */
public class JSONException extends Exception {
    private static final long serialVersionUID = 1L;

    public JSONException(String message) {
        super(message);
    }

    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }

    public JSONException(Throwable cause) {
        super(cause == null ? null : cause.toString(), cause);
    }
}

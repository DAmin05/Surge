package dev.surge.admission.tokens;

/**
 * Token types, carried in the JWT header's {@code typ} (explicit typing, RFC 8725) and
 * signed with separate key pairs. The gateway binds each route to one type, so a
 * session token can never be replayed as an admission token.
 */
public enum TokenType {
    SESSION("surge-session+jwt", "session"),
    ADMISSION("surge-admission+jwt", "admission");

    /** JWT header {@code typ}. */
    public final String header;
    /** Key directory under the keyset root. */
    public final String dir;

    TokenType(String header, String dir) {
        this.header = header;
        this.dir = dir;
    }
}

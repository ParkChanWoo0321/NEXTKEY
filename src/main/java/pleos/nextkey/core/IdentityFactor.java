package pleos.nextkey.core;

/** Providers bind their proof to this exact account and challenge. */
public interface IdentityFactor {
    void begin(String accountId,String challengeId);
    boolean verify(String accountId,String challengeId,String proof);
}

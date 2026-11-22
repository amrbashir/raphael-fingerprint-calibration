package me.amrbashir.raphael.fingerprint.calibration;

/** A preflighted display; restoration must also work after a partial write failure. */
interface DisplayProfile {
    void prepare() throws Exception;
    void apply(String operation) throws Exception;
    void verifyState() throws Exception;
    boolean restore();
}

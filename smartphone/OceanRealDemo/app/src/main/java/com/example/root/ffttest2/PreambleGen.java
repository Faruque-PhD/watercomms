package com.example.root.ffttest2;

/**
 * Utility class for generating preamble signals for synchronization and sounding.
 */
public class PreambleGen {
    /**
     * Generates Alice's initial Sounding signal to probe the acoustic channel.
     */
    public static short[] sounding_signal_s() {
        return SymbolGeneration.generatePreamble(Constants.pn60_bits, Constants.valid_carrier_data,
                Constants.chanest_symreps, true, Constants.SignalType.Sounding); }

    public static short[] preamble_s() {
        return Utils.convert_s(preamble_d());
    }

    public static double[] preamble_d() { return (Constants.naiser); }
}

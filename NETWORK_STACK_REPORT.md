# Underwater Messaging Network Stack Report

This report provides a detailed analysis of the network stack and communication protocols implemented in the Underwater Messaging system (SIGCOMM '22).

## 1. System Overview
The system enables real-time underwater text messaging between two mobile devices (Alice and Bob) using acoustic communication. It leverages standard smartphone hardware (microphones and speakers) and operates in the acoustic frequency range (typically 1kHz - 4kHz).

## 2. Network Architecture and Protocol

The system uses a point-to-point handshake protocol to adapt to the highly dynamic underwater acoustic channel.

### 2.1 Communication Flow (Handshake)
The communication is managed by `SendChirpAsyncTask.java` and follows these phases:

1.  **Sounding Phase (Alice -> Bob)**:
    *   Alice initiates communication by sending a **Sounding Signal**.
    *   The signal consists of a **Preamble** (for detection and synchronization) followed by several **Training OFDM Symbols**.
    *   Code Reference: `PreambleGen.sounding_signal_s()`, `SymbolGeneration.generatePreamble()`.

2.  **Feedback Phase (Bob -> Alice)**:
    *   Bob continuously listens for the preamble.
    *   Upon detection, Bob extracts the training symbols and performs **Channel Estimation** to calculate the Signal-to-Noise Ratio (SNR) for each OFDM subcarrier.
    *   Bob selects the "valid" subcarriers (those with SNR above a threshold) and encodes this frequency information into a **Feedback Signal**.
    *   The Feedback Signal is a simple acoustic signal (dual-tone) indicating the start and end frequencies of the optimal band.
    *   Code Reference: `ChannelEstimate.java`, `FeedbackSignal.java`.

3.  **Data Phase (Alice -> Bob)**:
    *   Alice receives the feedback signal and parses the optimal frequency range.
    *   She then transmits the **Data Packet**, which is an OFDM signal containing the encoded message bits, restricted to the optimal subcarriers identified by Bob.
    *   Bob receives the data, performs equalization, demodulation, and decoding to retrieve the message.
    *   Code Reference: `Decoder.java`, `Modulation.java`.

## 3. Physical Layer (PHY)

### 3.1 Modulation: Acoustic OFDM
The system uses Orthogonal Frequency Division Multiplexing (OFDM) to combat multipath fading common in underwater environments.

*   **Sampling Rate (`fs`)**: 48,000 Hz.
*   **FFT Size (`Ns`)**: Default is 960 (configurable to 1920, 4800, 9600).
*   **Subcarrier Spacing**: `fs / Ns` (e.g., 50 Hz for `Ns=960`).
*   **Cyclic Prefix (`Cp`)**: 67 samples (for `Ns=960`) to mitigate Inter-Symbol Interference (ISI).
*   **Symbol Modulation**: Differential BPSK (D-BPSK). Differential modulation is used to handle phase rotations caused by Doppler shifts and multi-path without requiring complex absolute phase tracking.

### 3.2 Error Correction and Coding
*   **Convolutional Coding**: Supports rates 1/2 and 2/3.
*   **Decoding**: Viterbi algorithm implemented in native C++ (`native-lib.cpp`) for efficiency.
*   **Interleaving**: Bit shuffling (shuffling array with a seed) is used to handle burst errors.

### 3.3 Synchronization and Detection
*   **Preamble**: A specific pseudo-random noise sequence or a "Naiser" preamble.
*   **Detection**: Uses cross-correlation (`xcorr`) between the received signal and the known preamble.
*   **Naiser Method**: An alternative robust detection method implemented in `Naiser.java`.

## 4. Signal Processing and Algorithms

### 4.1 Equalization
To correct channel distortions, the system employs both:
*   **Frequency-Domain Equalization (FDE)**: Performed in the `Decoder.java` using weights derived from training symbols.
*   **Time-Domain Equalization**: Mentioned in the MATLAB scripts (`Time_equalizer_estimation.m`) for potentially more robust recovery.

### 4.2 Adaptive Bandwidth Selection
A key feature of the SIGCOMM '22 paper is the ability to adapt the communication bandwidth. Bob estimates the SNR across the entire available spectrum and Alice narrows her transmission to only the high-quality subcarriers.

## 5. Hardware/Software Integration

### 5.1 Android Audio Interface
*   **Recording**: `AudioRecord` API captures 16-bit PCM audio from the microphone.
*   **Playback**: `AudioTrack` API plays back the generated acoustic signals.
*   **Real-time Processing**: Java-based logic coordinates the high-level protocol, while JNI/C++ handles the heavy FFT and Viterbi computations.

### 5.2 MATLAB Offline Decoder
The `Matlab_Decoder/` directory contains an offline version of the stack, used for research and reproduction of results. It implements the same logic (synchronization, FDE/TDE, Viterbi) but provides more extensive visualization and debugging tools.

## 6. Key Configuration Parameters (Constants.java)
*   `Ns`: OFDM symbol length (960).
*   `fs`: Sampling frequency (48000).
*   `f_range`: Default frequency range (1000 Hz - 4000 Hz).
*   `FEEDBACK_SNR_THRESH`: SNR threshold for subcarrier selection (13 dB).
*   `CODING`: Toggle for convolutional coding (true).

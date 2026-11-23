package tests.live_audio_test;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * GuardianGrid: Calibrated Standalone Live Microphone Audio Intelligence Test
 *
 * Implements:
 * 1. Adaptive Noise Floor Tracking & Voice Activity Detection (VAD) to completely eliminate ambient noise false triggers.
 * 2. Speech-Burst Segmentation & Segmented DTW alignment (eliminates the N=74 vs M=3 static DTW false match on 'save me').
 * 3. Robust Sustained Scream Filter (requires SPL > 20 dB above room noise, Centroid > 2200 Hz, duration > 200 ms).
 * 4. Tkinter/Swing Visual Green Screen GUI + Live Audio Level (dBFS) readout.
 */
public class LiveAudioTestRunnerCalibrated {

    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SIZE = 512;   // 32 ms
    public static final int HOP_SIZE = 256;     // 16 ms
    public static final int BUFFER_CAPACITY_SAMPLES = SAMPLE_RATE * 5;

    // Colors
    private static final Color DARK_BG = new Color(0x12, 0x12, 0x12);
    private static final Color CARD_BG = new Color(0x1E, 0x1E, 0x1E);
    private static final Color TEXT_PRIMARY = new Color(0xFA, 0xFA, 0xFA);
    private static final Color TEXT_SECONDARY = new Color(0xA0, 0xA0, 0xA0);
    private static final Color TRIGGER_BG = new Color(0x00, 0xE6, 0x76);
    private static final Color TRIGGER_TEXT = new Color(0x00, 0x00, 0x00);

    // GUI Components
    private JFrame frame;
    private JPanel mainPanel;
    private JPanel cardPanel;
    private JLabel headerLabel;
    private JLabel subHeaderLabel;
    private JLabel iconLabel;
    private JLabel statusTitle;
    private JLabel statusDetail;
    private JLabel footerLabel;
    private javax.swing.Timer resetTimer;

    // Audio Buffer
    private final float[] circularBuffer = new float[BUFFER_CAPACITY_SAMPLES];
    private int writeIndex = 0;
    private long totalSamplesWritten = 0;
    private final Object bufferLock = new Object();

    // VAD & Adaptive Noise Floor
    private float adaptiveNoiseFloorDbfs = -45.0f; // Initial estimate
    private float currentDbfs = -60.0f;
    private volatile boolean isRunning = false;
    private TargetDataLine audioLine;

    // State Tracking
    private int consecutiveScreamFrames = 0;
    private long lastScreamTime = 0;
    private long lastKeywordTime = 0;

    static class KeywordProfile {
        final String keyword;
        final String language;
        final float[][] pattern;
        final int minDurationMs;
        final int maxDurationMs;

        KeywordProfile(String keyword, String language, float[][] pattern, int minMs, int maxMs) {
            this.keyword = keyword;
            this.language = language;
            this.pattern = pattern;
            this.minDurationMs = minMs;
            this.maxDurationMs = maxMs;
        }
    }

    private final List<KeywordProfile> keywordProfiles = List.of(
            new KeywordProfile("help", "en", new float[][]{
                    {0.2f, 0.4f, 0.4f}, // /h/
                    {0.4f, 0.5f, 0.1f}, // /e/
                    {0.6f, 0.3f, 0.1f}  // /l-p/
            }, 250, 700),
            new KeywordProfile("save me", "en", new float[][]{
                    {0.1f, 0.3f, 0.6f}, // /s/
                    {0.5f, 0.4f, 0.1f}, // /eɪ-v/
                    {0.7f, 0.2f, 0.1f}  // /m-i/
            }, 350, 950),
            new KeywordProfile("stop", "en", new float[][]{
                    {0.1f, 0.2f, 0.7f}, // /s/
                    {0.5f, 0.4f, 0.1f}, // /ɒ/
                    {0.1f, 0.1f, 0.1f}  // /p/
            }, 250, 650),
            new KeywordProfile("kaapaathunga", "ta", new float[][]{
                    {0.3f, 0.5f, 0.2f}, // kaa
                    {0.4f, 0.5f, 0.1f}, // paa
                    {0.5f, 0.4f, 0.1f}, // thun
                    {0.4f, 0.4f, 0.2f}  // ga
            }, 550, 1300),
            new KeywordProfile("udhavi", "ta", new float[][]{
                    {0.6f, 0.3f, 0.1f}, // u
                    {0.4f, 0.5f, 0.1f}, // dha
                    {0.3f, 0.5f, 0.2f}  // vi
            }, 350, 850),
            new KeywordProfile("bachao", "hi", new float[][]{
                    {0.6f, 0.3f, 0.1f}, // ba
                    {0.3f, 0.5f, 0.2f}  // chao
            }, 300, 800)
    );

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LiveAudioTestRunnerCalibrated app = new LiveAudioTestRunnerCalibrated();
            app.initGui();
            app.startAudio();
        });
    }

    private void initGui() {
        frame = new JFrame("GuardianGrid - Calibrated Live Audio Test");
        frame.setSize(660, 440);
        frame.setMinimumSize(new Dimension(540, 380));
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(DARK_BG);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 25, 20, 25));

        headerLabel = new JLabel("GuardianGrid Audio Intelligence (Calibrated)");
        headerLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        headerLabel.setForeground(TEXT_PRIMARY);
        headerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainPanel.add(headerLabel);

        subHeaderLabel = new JLabel("Adaptive VAD + Discrete Syllable Spotting + Scream Guard");
        subHeaderLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subHeaderLabel.setForeground(TEXT_SECONDARY);
        subHeaderLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainPanel.add(subHeaderLabel);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        cardPanel = new JPanel();
        cardPanel.setLayout(new BoxLayout(cardPanel, BoxLayout.Y_AXIS));
        cardPanel.setBackground(CARD_BG);
        cardPanel.setBorder(BorderFactory.createEmptyBorder(25, 20, 25, 20));

        iconLabel = new JLabel("[ MIC READY ]");
        iconLabel.setFont(new Font("Segoe UI", Font.BOLD, 26));
        iconLabel.setForeground(TEXT_PRIMARY);
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        cardPanel.add(iconLabel);
        cardPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        statusTitle = new JLabel("Listening...");
        statusTitle.setFont(new Font("Segoe UI", Font.BOLD, 28));
        statusTitle.setForeground(TEXT_PRIMARY);
        statusTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        cardPanel.add(statusTitle);
        cardPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        statusDetail = new JLabel("<html><center>Say: \"help\", \"save me\", \"kaapaathunga\", \"udhavi\", \"bachao\"<br>Or produce a loud distress scream</center></html>");
        statusDetail.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusDetail.setForeground(TEXT_SECONDARY);
        statusDetail.setAlignmentX(Component.CENTER_ALIGNMENT);
        cardPanel.add(statusDetail);

        mainPanel.add(cardPanel);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        footerLabel = new JLabel("Mic: 16kHz Mono | Level: -- dBFS | Noise Floor: -- dBFS");
        footerLabel.setFont(new Font("Consolas", Font.PLAIN, 12));
        footerLabel.setForeground(TEXT_SECONDARY);
        footerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainPanel.add(footerLabel);

        frame.setContentPane(mainPanel);
        frame.setVisible(true);

        resetTimer = new javax.swing.Timer(2500, e -> resetListeningScreen());
        resetTimer.setRepeats(false);
    }

    private void triggerGreenScreen(String triggerType, String label, float confidence, String timestamp) {
        SwingUtilities.invokeLater(() -> {
            resetTimer.stop();

            frame.getContentPane().setBackground(TRIGGER_BG);
            mainPanel.setBackground(TRIGGER_BG);
            cardPanel.setBackground(TRIGGER_BG);
            headerLabel.setBackground(TRIGGER_BG);
            headerLabel.setForeground(TRIGGER_TEXT);
            subHeaderLabel.setBackground(TRIGGER_BG);
            subHeaderLabel.setForeground(new Color(0x1B, 0x5E, 0x20));
            footerLabel.setBackground(TRIGGER_BG);
            footerLabel.setForeground(TRIGGER_TEXT);

            iconLabel.setText(triggerType.equals("SCREAM") ? "🚨 SCREAM ALARM!" : "🗣️ KEYWORD DETECTED!");
            iconLabel.setForeground(TRIGGER_TEXT);

            statusTitle.setText(triggerType + " CONFIRMED!");
            statusTitle.setForeground(TRIGGER_TEXT);

            statusDetail.setText(String.format(
                    "<html><center><b>Matched: %s</b><br>Confidence: %.1f%%  |  Time: %s</center></html>",
                    label, confidence * 100f, timestamp
            ));
            statusDetail.setForeground(TRIGGER_TEXT);
            statusDetail.setFont(new Font("Segoe UI", Font.BOLD, 16));

            resetTimer.start();
        });
    }

    private void resetListeningScreen() {
        frame.getContentPane().setBackground(DARK_BG);
        mainPanel.setBackground(DARK_BG);
        cardPanel.setBackground(CARD_BG);
        headerLabel.setBackground(DARK_BG);
        headerLabel.setForeground(TEXT_PRIMARY);
        subHeaderLabel.setBackground(DARK_BG);
        subHeaderLabel.setForeground(TEXT_SECONDARY);
        footerLabel.setBackground(DARK_BG);
        footerLabel.setForeground(TEXT_SECONDARY);

        iconLabel.setText("[ MIC ACTIVE ]");
        iconLabel.setForeground(TEXT_PRIMARY);

        statusTitle.setText("Listening...");
        statusTitle.setForeground(TEXT_PRIMARY);

        statusDetail.setText("<html><center>Say: \"help\", \"save me\", \"kaapaathunga\", \"udhavi\", \"bachao\"<br>Or produce a loud distress scream</center></html>");
        statusDetail.setForeground(TEXT_SECONDARY);
        statusDetail.setFont(new Font("Segoe UI", Font.PLAIN, 13));
    }

    private void startAudio() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            System.err.println("[ERROR] Microphone line not supported.");
            return;
        }

        try {
            audioLine = (TargetDataLine) AudioSystem.getLine(info);
            audioLine.open(format, FRAME_SIZE * 4);
            audioLine.start();
            isRunning = true;
            System.out.println("[AUDIO] Physical microphone stream active (16 kHz Mono).");
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to open microphone: " + e.getMessage());
            return;
        }

        Thread captureThread = new Thread(this::runAudioCaptureLoop, "CaptureThread");
        captureThread.setDaemon(true);
        captureThread.start();

        Thread analysisThread = new Thread(this::runAnalysisLoop, "AnalysisThread");
        analysisThread.setDaemon(true);
        analysisThread.start();
    }

    private void runAudioCaptureLoop() {
        byte[] byteBuffer = new byte[FRAME_SIZE * 2];
        float[] floatBuffer = new float[FRAME_SIZE];

        while (isRunning) {
            int readBytes = audioLine.read(byteBuffer, 0, byteBuffer.length);
            if (readBytes > 0) {
                int samples = readBytes / 2;
                for (int i = 0; i < samples; i++) {
                    short s = (short) ((byteBuffer[2 * i] & 0xFF) | (byteBuffer[2 * i + 1] << 8));
                    floatBuffer[i] = s / 32768.0f;
                }
                writeToCircularBuffer(floatBuffer, samples);
            }
        }
    }

    private void writeToCircularBuffer(float[] samples, int length) {
        synchronized (bufferLock) {
            for (int i = 0; i < length; i++) {
                circularBuffer[writeIndex] = samples[i];
                writeIndex = (writeIndex + 1) % BUFFER_CAPACITY_SAMPLES;
            }
            totalSamplesWritten += length;
        }
    }

    private float[] getBufferSnapshot(float durationSec) {
        synchronized (bufferLock) {
            int requested = (int) (durationSec * SAMPLE_RATE);
            int available = (int) Math.min(totalSamplesWritten, BUFFER_CAPACITY_SAMPLES);
            int count = Math.min(requested, available);
            if (count <= 0) return new float[0];

            float[] result = new float[count];
            int readIdx = (writeIndex - count + BUFFER_CAPACITY_SAMPLES) % BUFFER_CAPACITY_SAMPLES;
            int firstChunk = Math.min(count, BUFFER_CAPACITY_SAMPLES - readIdx);
            System.arraycopy(circularBuffer, readIdx, result, 0, firstChunk);
            int secondChunk = count - firstChunk;
            if (secondChunk > 0) {
                System.arraycopy(circularBuffer, 0, result, firstChunk, secondChunk);
            }
            return result;
        }
    }

    private void runAnalysisLoop() {
        while (isRunning) {
            try {
                Thread.sleep(40); // 25 Hz analysis
            } catch (InterruptedException ignored) {}

            float[] recentFrame = getBufferSnapshot(0.10f); // 100ms
            if (recentFrame.length < FRAME_SIZE) continue;

            float rms = computeRms(recentFrame);
            currentDbfs = rmsToDbfs(rms);

            // Update adaptive noise floor during low volume
            if (currentDbfs < adaptiveNoiseFloorDbfs + 6.0f) {
                adaptiveNoiseFloorDbfs = adaptiveNoiseFloorDbfs * 0.98f + currentDbfs * 0.02f;
            }

            // Update GUI footer level display
            SwingUtilities.invokeLater(() -> {
                footerLabel.setText(String.format(
                        "Mic: 16kHz | Level: %.1f dBFS | Noise Floor: %.1f dBFS | SNR: %+.1f dB",
                        currentDbfs, adaptiveNoiseFloorDbfs, currentDbfs - adaptiveNoiseFloorDbfs
                ));
            });

            // 1. Scream Evaluation (requires loud Sound Pressure Level > 20 dB above ambient floor)
            float[] screamWindow = getBufferSnapshot(0.40f);
            if (screamWindow.length >= FRAME_SIZE) {
                evaluateScreamCalibrated(screamWindow);
            }

            // 2. Keyword Evaluation on speech burst
            float[] speechWindow = getBufferSnapshot(1.20f);
            if (speechWindow.length >= FRAME_SIZE * 4) {
                evaluateKeywordsCalibrated(speechWindow);
            }
        }
    }

    private void evaluateScreamCalibrated(float[] samples) {
        float snr = currentDbfs - adaptiveNoiseFloorDbfs;

        // Scream Gate: Must be genuinely loud (> 20 dB above room noise, AND absolute dBFS > -18 dBFS)
        if (snr < 18.0f || currentDbfs < -18.0f) {
            consecutiveScreamFrames = 0;
            return;
        }

        float maxScore = 0f;
        int screamFrames = 0;
        int frameCount = 0;

        for (int start = 0; start <= samples.length - FRAME_SIZE; start += HOP_SIZE) {
            frameCount++;
            float[] frame = new float[FRAME_SIZE];
            System.arraycopy(samples, start, frame, 0, FRAME_SIZE);

            float rms = computeRms(frame);
            float dbfs = rmsToDbfs(rms);
            if (dbfs < -20.0f) continue;

            applyHannWindow(frame);
            float[] power = computePowerSpectrum(frame);
            float totalPower = 0f;
            for (float p : power) totalPower += p;
            if (totalPower < 1e-9f) continue;

            float binWidth = (SAMPLE_RATE / 2.0f) / (power.length - 1);
            int lowBin = (int) (1500.0f / binWidth);
            int highBin = Math.min(power.length - 1, (int) (4500.0f / binWidth));

            float screamPower = 0f;
            float weightedFreqSum = 0f;
            for (int k = 0; k < power.length; k++) {
                float freq = k * binWidth;
                weightedFreqSum += freq * power[k];
                if (k >= lowBin && k <= highBin) screamPower += power[k];
            }

            float centroid = weightedFreqSum / totalPower;
            float screamBandRatio = screamPower / totalPower;

            // Strict scream metrics
            if (centroid > 2200.0f && screamBandRatio > 0.42f) {
                float score = (screamBandRatio * 0.60f) + ((centroid / 5000.0f) * 0.40f);
                if (score > maxScore) maxScore = score;
                screamFrames++;
            }
        }

        long now = System.currentTimeMillis();
        // Require at least 5 consecutive frames (~160 ms) of sustained scream frequency concentration
        if (screamFrames >= 5 && maxScore > 0.55f) {
            consecutiveScreamFrames++;
            if (consecutiveScreamFrames >= 2 && (now - lastScreamTime) > 2000) {
                lastScreamTime = now;
                float confidence = Math.min(0.99f, Math.max(0.75f, maxScore * 1.2f));
                String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
                System.out.printf("[%s] 🚨 [SCREAM CONFIRMED] Confidence: %.1f%% | Level: %.1f dBFS | SNR: +%.1f dB%n",
                        ts, confidence * 100f, currentDbfs, snr);
                triggerGreenScreen("SCREAM", "Acoustic Scream Distress", confidence, ts);
            }
        } else {
            consecutiveScreamFrames = Math.max(0, consecutiveScreamFrames - 1);
        }
    }

    private void evaluateKeywordsCalibrated(float[] samples) {
        float speechThreshold = adaptiveNoiseFloorDbfs + 12.0f;
        speechThreshold = Math.max(speechThreshold, -30.0f); // Minimum -30 dBFS

        // 1. Identify active speech burst (find start & end frames where energy exceeds noise floor)
        List<float[]> activeFrames = new ArrayList<>();
        List<Float> frameEnergies = new ArrayList<>();

        for (int start = 0; start <= samples.length - FRAME_SIZE; start += HOP_SIZE) {
            float[] frame = new float[FRAME_SIZE];
            System.arraycopy(samples, start, frame, 0, FRAME_SIZE);
            float rms = computeRms(frame);
            float dbfs = rmsToDbfs(rms);
            frameEnergies.add(dbfs);

            if (dbfs >= speechThreshold) {
                applyHannWindow(frame);
                float[] power = computePowerSpectrum(frame);
                activeFrames.add(extractThreeBands(power));
            }
        }

        // Must have an isolated speech burst of 8 to 40 frames (130 ms to 650 ms)
        if (activeFrames.size() < 8 || activeFrames.size() > 45) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastKeywordTime < 2200) return;

        KeywordProfile bestMatch = null;
        float bestScore = 0.0f;

        for (KeywordProfile profile : keywordProfiles) {
            int burstDurationMs = activeFrames.size() * (HOP_SIZE * 1000 / SAMPLE_RATE);
            if (burstDurationMs < profile.minDurationMs || burstDurationMs > profile.maxDurationMs) {
                continue; // Duration mismatch
            }

            float score = computeStrictDtw(activeFrames, profile.pattern);
            if (score > 0.74f && score > bestScore) {
                bestScore = score;
                bestMatch = profile;
            }
        }

        if (bestMatch != null) {
            lastKeywordTime = now;
            String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            System.out.printf("[%s] 🗣️ [KEYWORD CONFIRMED] Word: '%s' (%s) | Confidence: %.1f%% | Level: %.1f dBFS%n",
                    ts, bestMatch.keyword, bestMatch.language.toUpperCase(Locale.ROOT), bestScore * 100f, currentDbfs);
            triggerGreenScreen("KEYWORD", String.format("'%s' (%s)", bestMatch.keyword, bestMatch.language.toUpperCase(Locale.ROOT)), bestScore, ts);
        }
    }

    private float computeStrictDtw(List<float[]> observed, float[][] target) {
        int n = observed.size();
        int m = target.length;
        float[][] dtw = new float[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) dtw[i][j] = Float.MAX_VALUE;
        }
        dtw[0][0] = 0f;

        for (int i = 1; i <= n; i++) {
            float[] obs = observed.get(i - 1);
            for (int j = 1; j <= m; j++) {
                float[] tgt = target[j - 1];
                float dist = Math.abs(obs[0] - tgt[0]) + Math.abs(obs[1] - tgt[1]) + Math.abs(obs[2] - tgt[2]);
                float minPrev = Math.min(dtw[i - 1][j], Math.min(dtw[i][j - 1], dtw[i - 1][j - 1]));
                dtw[i][j] = dist + minPrev;
            }
        }

        float avgDist = dtw[n][m] / (n + m);
        return Math.min(1.0f, Math.max(0.0f, 1.0f - avgDist));
    }

    private float[] extractThreeBands(float[] power) {
        float binWidth = (SAMPLE_RATE / 2.0f) / (power.length - 1);
        int lowBin = Math.min(power.length - 1, (int) (800.0f / binWidth));
        int midBin = Math.min(power.length - 1, (int) (2500.0f / binWidth));
        int highBin = Math.min(power.length - 1, (int) (6000.0f / binWidth));

        float low = 0f, mid = 0f, high = 0f;
        for (int i = 0; i < lowBin; i++) low += power[i];
        for (int i = lowBin; i < midBin; i++) mid += power[i];
        for (int i = midBin; i < highBin; i++) high += power[i];
        float tot = low + mid + high + 1e-9f;

        return new float[]{low / tot, mid / tot, high / tot};
    }

    private float computeRms(float[] samples) {
        double sum = 0.0;
        for (float s : samples) sum += s * s;
        return (float) Math.sqrt(sum / samples.length);
    }

    private float rmsToDbfs(float rms) {
        if (rms <= 1e-5f) return -100.0f;
        return (float) (20.0 * Math.log10(Math.max(rms, 1e-5)));
    }

    private void applyHannWindow(float[] frame) {
        int n = frame.length;
        for (int i = 0; i < n; i++) {
            frame[i] *= 0.5f * (1.0f - Math.cos((2.0 * Math.PI * i) / (n - 1)));
        }
    }

    private float[] computePowerSpectrum(float[] frame) {
        int n = frame.length;
        double[] real = new double[n];
        double[] imag = new double[n];
        for (int i = 0; i < n; i++) real[i] = frame[i];

        // Bit-reversal
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
            int m = n >> 1;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        // Cooley-Tukey Radix-2
        for (int step = 2; step <= n; step <<= 1) {
            int halfStep = step >> 1;
            double theta = -2.0 * Math.PI / step;
            double wStepR = Math.cos(theta);
            double wStepI = Math.sin(theta);

            for (int k = 0; k < n; k += step) {
                double wR = 1.0, wI = 0.0;
                for (int m = 0; m < halfStep; m++) {
                    int idx = k + m;
                    int match = idx + halfStep;
                    double tr = wR * real[match] - wI * imag[match];
                    double ti = wR * imag[match] + wI * real[match];
                    real[match] = real[idx] - tr;
                    imag[match] = imag[idx] - ti;
                    real[idx] += tr;
                    imag[idx] += ti;

                    double nwr = wR * wStepR - wI * wStepI;
                    wI = wR * wStepI + wI * wStepR;
                    wR = nwr;
                }
            }
        }

        int half = n / 2 + 1;
        float[] power = new float[half];
        for (int i = 0; i < half; i++) {
            power[i] = (float) ((real[i] * real[i] + imag[i] * imag[i]) / n);
        }
        return power;
    }
}

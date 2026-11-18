package com.github.timan1802.typingbongocat;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.Timer;

public class BongoCatAnimator extends JComponent implements ComponentListener, Disposable {

    private static final Logger LOG = Logger.getInstance(BongoCatAnimator.class);

    // --- 상수 정의 ---
    private static final int TYPING_INTERVAL_MS = 500; // 연속 입력으로 간주할 시간 (ms)
    private static final int MIDDLE_STATE_DELAY_MS = 500; // 중간 상태로 바뀌기까지의 지연 시간 (ms)
    private static final int HIDE_DELAY_MS = 1000; // 이미지가 사라지기까지의 지연 시간 (ms)
    private static final int EDGE_MARGIN = 16;
    private static final int FLIP_DURATION_MS = 400;
    // --- BAM 효과 상수 ---
    private static final int BAM_THRESHOLD = 20; // 'BAM!' 효과 발동 연속 입력 횟수
    private static final int BAM_FRAME_INTERVAL_MS = 25; // 'BAM!' 애니메이션 프레임 간격 (약 40fps)
    private static final int BAM_SHAKE_DURATION_MS = 300; // 'BAM!' 효과 지속 시간 (ms)
    private static final int BAM_GROW_FADE_DURATION_MS = 1200; // 'BAM!' 확대/소멸 효과 시간 (ms)
    private static final int BAM_TOTAL_DURATION_MS = BAM_SHAKE_DURATION_MS + BAM_GROW_FADE_DURATION_MS;
    private static final int BAM_SHAKE_INTENSITY = 5; // 'BAM!' 효과 흔들림 강도 (pixels)
    private static final float BAM_MAX_SCALE = 2.0f; // 'BAM!' 효과 최대 확대 배율

    // --- 이미지 리소스 로딩 (모두 100px 너비로 미리 맞춘 파일이라 런타임 리사이즈 없음) ---
    private static final BufferedImage BONGO_RIGHT_IMG = loadImage("/img/cat/100/bongo_right.png");
    private static final BufferedImage BONGO_LEFT_IMG = loadImage("/img/cat/100/bongo_left.png");
    private static final BufferedImage BONGO_MIDDLE_IMG = loadImage("/img/cat/100/bongo_middle.png");
    private static final BufferedImage BONGO_BAM_IMG = loadImage("/img/cat/100/bongo_bam.png");

    private final JComponent parent;
    private final Editor editor;
    private final Random random = new Random();
    // --- 타이머 ---
    private final Timer middleTimer;
    private final Timer hideTimer;
    private final Timer bamEffectTimer;
    private final Timer flipTimer;
    private long flipStartTime = 0; // 0 = not flipping
    // --- 상태 변수 ---
    private volatile BufferedImage currentImage;
    private long lastKeyPressTime = 0;
    private boolean lastHandWasRight = false;
    private int consecutiveKeyPressCount = 0;
    private volatile boolean isBamEffectActive = false;
    private long bamStartTime;
    // BAM 효과 중 마지막으로 입력된 캐럿 위치 (null 이면 대기 중인 입력 없음)
    private Point queuedCaretPositionDuringBam;

    private int x;
    private int y;
    // BAM 애니메이션을 위한 변수
    private float currentScale = 1.0f;
    private float currentAlpha = 1.0f;
    // 직전에 그린 이미지 영역. 다음 repaint 때 지워야 할 영역으로 사용 (null 이면 그린 것 없음)
    private Rectangle lastImageArea;

    public BongoCatAnimator(Editor editor) {
        this.editor = editor;
        this.parent = editor.getContentComponent();
        parent.add(this);
        updateBounds();
        setVisible(true);
        parent.addComponentListener(this);

        middleTimer = new Timer(MIDDLE_STATE_DELAY_MS, e -> {
            // 사용자가 입력을 멈췄을 때 호출됩니다. 이때 BAM! 효과를 체크합니다.
            if (consecutiveKeyPressCount >= BAM_THRESHOLD) {
                showBamEffect();
            } else {
                // 일반적인 멈춤: 중간 상태로 전환합니다.
                currentImage = BONGO_MIDDLE_IMG;
                repaintImageArea();
            }
            // 효과를 처리했으므로 연속 입력 횟수를 초기화합니다.
            consecutiveKeyPressCount = 0;
        });
        middleTimer.setRepeats(false);

        hideTimer = new Timer(HIDE_DELAY_MS, e -> {
            currentImage = null;
            repaintImageArea();
        });
        hideTimer.setRepeats(false);

        // BAM 효과 전체(흔들림 + 확대/소멸)를 제어하는 타이머
        bamEffectTimer = new Timer(BAM_FRAME_INTERVAL_MS, e -> {
            long elapsed = System.currentTimeMillis() - bamStartTime;

            if (elapsed >= BAM_TOTAL_DURATION_MS) {
                finishBamEffect();
            } else if (elapsed >= BAM_SHAKE_DURATION_MS) {
                // --- 확대 및 소멸 단계 ---
                float progress = (float) (elapsed - BAM_SHAKE_DURATION_MS) / BAM_GROW_FADE_DURATION_MS;
                currentScale = 1.0f + (BAM_MAX_SCALE - 1.0f) * progress;
                currentAlpha = 1.0f - progress;
            } else {
                // --- 흔들림 단계 ---
                currentScale = 1.0f;
                currentAlpha = 1.0f;
            }

            repaintImageArea();
        });
        bamEffectTimer.setRepeats(true);

        flipTimer = new Timer(BAM_FRAME_INTERVAL_MS, e -> {
            if (System.currentTimeMillis() - flipStartTime >= FLIP_DURATION_MS) {
                stopFlip();
            }
            repaintImageArea();
        });
    }

    public void flip() {
        if (currentImage == null || isBamEffectActive) {
            return;
        }
        flipStartTime = System.currentTimeMillis();
        flipTimer.start();
    }

    private void stopFlip() {
        flipStartTime = 0;
        flipTimer.stop();
    }

    private static BufferedImage loadImage(String path) {
        try (InputStream stream = BongoCatAnimator.class.getResourceAsStream(path)) {
            if (stream == null) {
                LOG.warn("Resource not found: " + path);
                return null;
            }
            return ImageIO.read(stream);
        } catch (IOException e) {
            LOG.error("Image loading failure: " + path, e);
            return null;
        }
    }

    /**
     * @param caretPosition 뷰포트(이 컴포넌트) 기준 캐럿 줄의 좌상단 좌표
     */
    public void update(Point caretPosition) {
        // BAM 효과가 진행 중일 때는 위치를 바꾸지 않고 마지막 입력 위치만 저장
        if (isBamEffectActive) {
            queuedCaretPositionDuringBam = caretPosition;
            return;
        }

        // 숨어 있는 동안은 bounds 동기화를 건너뛰므로, 표시 직전에 뷰포트와 맞춥니다.
        // (caretPosition 은 현재 뷰포트 기준 좌표)
        updateBounds();
        this.x = caretPosition.x;
        this.y = caretPosition.y;

        // 새로운 키 입력이 있었으므로, 기존의 모든 타이머와 효과를 중지합니다.
        middleTimer.stop();
        hideTimer.stop();
        bamEffectTimer.stop();

        long now = System.currentTimeMillis();
        boolean isConsecutive = (now - lastKeyPressTime) < TYPING_INTERVAL_MS;

        if (isConsecutive) {
            consecutiveKeyPressCount++;
            // 연속 입력: 손을 교체합니다.
            currentImage = lastHandWasRight ? BONGO_LEFT_IMG : BONGO_RIGHT_IMG;
            lastHandWasRight = !lastHandWasRight;
        } else {
            // 새로운 입력 시작: 카운터를 1로 설정하고 오른손으로 시작합니다.
            consecutiveKeyPressCount = 1;
            currentImage = BONGO_RIGHT_IMG;
            lastHandWasRight = true;
        }

        // 타이핑이 멈췄을 때를 대비해 타이머를 다시 시작합니다.
        middleTimer.restart();
        hideTimer.restart();

        lastKeyPressTime = now;
        repaintImageArea();
    }

    private void showBamEffect() {
        // 다른 타이머 중지
        middleTimer.stop();
        hideTimer.stop();

        stopFlip();
        // BAM 효과 상태 설정
        currentImage = BONGO_BAM_IMG;
        isBamEffectActive = true;
        bamStartTime = System.currentTimeMillis();
        // 애니메이션 변수 초기화
        currentScale = 1.0f;
        currentAlpha = 1.0f;

        repaintImageArea();
        bamEffectTimer.start();
    }

    private void finishBamEffect() {
        isBamEffectActive = false;
        currentImage = null;
        bamEffectTimer.stop();
        repaintImageArea();

        if (queuedCaretPositionDuringBam == null) {
            return;
        }
        // BAM 중 들어온 입력은 새 타이핑 시퀀스의 첫 입력으로만 취급합니다.
        // (대기 중 입력 수를 연속 입력 수로 넘기면 BAM 이 연쇄 발동합니다.)
        Point queuedCaretPosition = queuedCaretPositionDuringBam;
        queuedCaretPositionDuringBam = null;
        lastKeyPressTime = 0;
        update(queuedCaretPosition);
    }

    /** [-BAM_SHAKE_INTENSITY, BAM_SHAKE_INTENSITY] 범위의 대칭 흔들림 오프셋 */
    private int randomShakeOffset() {
        return random.nextInt(BAM_SHAKE_INTENSITY * 2 + 1) - BAM_SHAKE_INTENSITY;
    }

    /** Bottom-right corner, or bottom-left while the caret is typing underneath it. */
    private boolean dodgesLeft(int imageWidth, int imageHeight) {
        Rectangle rightCorner = new Rectangle(
            getWidth() - imageWidth - EDGE_MARGIN, resolveDrawY(imageHeight), imageWidth, imageHeight);
        rightCorner.grow(EDGE_MARGIN, EDGE_MARGIN);
        return rightCorner.intersects(new Rectangle(this.x - 1, this.y, 2, editor.getLineHeight()));
    }

    private int clampDrawX(int imageWidth, int imageHeight) {
        if (dodgesLeft(imageWidth, imageHeight)) {
            return EDGE_MARGIN;
        }
        return Math.max(EDGE_MARGIN, getWidth() - imageWidth - EDGE_MARGIN);
    }

    private int resolveDrawY(int imageHeight) {
        return Math.max(0, getHeight() - imageHeight - EDGE_MARGIN);
    }

    /**
     * 현재 이미지가 차지할 수 있는 최대 영역. BAM 중에는 최대 확대 배율과 흔들림 폭까지 포함합니다.
     */
    private Rectangle imageArea(BufferedImage image) {
        int imgWidth = image.getWidth();
        int imgHeight = image.getHeight();
        Rectangle area = new Rectangle(clampDrawX(imgWidth, imgHeight), resolveDrawY(imgHeight), imgWidth, imgHeight);
        if (isBamEffectActive) {
            // BAM grows away from the corner by the full extra size, not half of it as with centre scaling.
            int growX = (int) Math.ceil(imgWidth * (BAM_MAX_SCALE - 1.0f)) + BAM_SHAKE_INTENSITY + 1;
            int growY = (int) Math.ceil(imgHeight * (BAM_MAX_SCALE - 1.0f)) + BAM_SHAKE_INTENSITY + 1;
            area.grow(growX, growY);
        } else if (flipStartTime != 0) {
            area.grow(imgWidth / 2, imgWidth / 2);
        }
        return area;
    }

    /**
     * 컴포넌트 전체가 아니라 직전 이미지 영역과 새 이미지 영역만 다시 그립니다.
     * 투명 오버레이라 repaint() 전체 호출은 에디터 본문 전체 다시 그리기로 이어지기 때문입니다.
     */
    private void repaintImageArea() {
        BufferedImage image = currentImage;
        Rectangle newImageArea = image == null ? null : imageArea(image);
        if (lastImageArea != null) {
            repaint(lastImageArea);
        }
        if (newImageArea != null) {
            repaint(newImageArea);
        }
        lastImageArea = newImageArea;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        BufferedImage image = currentImage;
        if (image == null) {
            return;
        }
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            int imgWidth = image.getWidth();
            int imgHeight = image.getHeight();
            int drawX = clampDrawX(imgWidth, imgHeight);
            int drawY = resolveDrawY(imgHeight);
            boolean onLeft = drawX == EDGE_MARGIN;

            if (isBamEffectActive) {
                // 흔들림 효과 적용
                if (System.currentTimeMillis() - bamStartTime < BAM_SHAKE_DURATION_MS) {
                    drawX += randomShakeOffset();
                    drawY += randomShakeOffset();
                }

                // 확대 및 투명도 효과 적용 (중심 기준 확대)
                int scaledWidth = (int) (imgWidth * currentScale);
                int scaledHeight = (int) (imgHeight * currentScale);
                // Anchored to the corner the cat sits in, so the 2x growth stays inside the viewport.
                int scaledDrawX = onLeft ? drawX : drawX - (scaledWidth - imgWidth);
                int scaledDrawY = drawY - (scaledHeight - imgHeight);

                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, currentAlpha))));
                g2d.drawImage(image, scaledDrawX, scaledDrawY, scaledWidth, scaledHeight, null);
            } else {
                if (flipStartTime != 0) {
                    double turn = (System.currentTimeMillis() - flipStartTime) / (double) FLIP_DURATION_MS;
                    g2d.rotate(2 * Math.PI * Math.min(1.0, turn), drawX + imgWidth / 2.0, drawY + imgHeight / 2.0);
                }
                g2d.drawImage(image, drawX, drawY, null);
            }
        } finally {
            g2d.dispose();
        }
    }

    // 스크롤할 때마다 에디터 본문이 움직여 이 이벤트들이 발생합니다.
    // 숨어 있을 때 setBounds 하면 매 스크롤마다 화면 전체 다시 그리기가 일어나므로, 보일 때만 맞춥니다.
    @Override
    public void componentResized(ComponentEvent e) { updateBoundsIfVisible(); }

    @Override
    public void componentMoved(ComponentEvent e) { updateBoundsIfVisible(); }

    private void updateBoundsIfVisible() {
        if (currentImage != null) {
            updateBounds();
        }
    }

    private void updateBounds() {
        this.setBounds(editor.getScrollingModel().getVisibleArea().getBounds());
    }

    @Override
    public void componentShown(ComponentEvent e) {
        // 표시 상태 변화는 위치·크기에 영향이 없으므로 처리하지 않습니다.
    }

    @Override
    public void componentHidden(ComponentEvent e) {
        // 표시 상태 변화는 위치·크기에 영향이 없으므로 처리하지 않습니다.
    }

    /**
     * 이 컴포넌트가 소멸될 때 호출되어, 부모로부터 자신을 제거하고 타이머를 중지하여 메모리 누수를 방지합니다.
     */
    @Override
    public void dispose() {
        parent.removeComponentListener(this);
        parent.remove(this);
        middleTimer.stop();
        hideTimer.stop();
        bamEffectTimer.stop();
        flipTimer.stop();
    }
}

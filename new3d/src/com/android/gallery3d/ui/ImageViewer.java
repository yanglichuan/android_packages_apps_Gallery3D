package com.android.gallery3d.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Bitmap.Config;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ImageViewer extends GLView {
    private static final String TAG = "ImageViewer";

    // TILE_SIZE must be 2^N - 2. We put one pixel border in each side of the
    // texture to avoid seams between tiles.
    private static final int TILE_SIZE = 254;
    private static final int TILE_BORDER = 1;
    private static final int UPLOAD_LIMIT = 4;

    private final Bitmap mScaledBitmaps[];
    private final BitmapTexture mBackupTexture;
    private final int mLevelCount;  // cache the value of mScaledBitmaps.length

    private int mCenterX = Integer.MIN_VALUE; // some invalid value
    private int mCenterY = Integer.MIN_VALUE;

    private float mScale = -1; // some invalid value;

    // The mLevel variable indicates which level of bitmap we should use.
    // Level 0 means the original full-sized bitmap, and a larger value means
    // a smaller scaled bitmap (The width and height of each scaled bitmap is
    // half size of the previous one). If the value is in [0, mLevelCount), we
    // use the bitmap in mScaledBitmaps[mLevel] for display, otherwise the value
    // is mLevelCount, and that means we use mBackupTexture for display.
    private int mLevel = 0;

    // The offsets of the (left, top) of the upper-left tile to the (left, top)
    // of the view.
    private int mOffsetX;
    private int mOffsetY;

    private int mUploadQuota;
    private boolean mRenderComplete;

    private final RectF mSourceRect = new RectF();
    private final RectF mTargetRect = new RectF();

    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mGestureDetector;

    private final HashMap<Long, Tile> mActiveTiles = new HashMap<Long, Tile>();
    private Iterator<Tile> mUploadIter;

    private Tile mRecycledHead = null;

    // The width and height of the full-sized bitmap
    private final int mImageWidth;
    private final int mImageHeight;

    // Temp variables to avoid memory allocation
    private final Rect mTileRange = new Rect();
    private final Rect mActiveRange[] = {new Rect(), new Rect()};

    private final Uploader mUploader = new Uploader();

    public ImageViewer(Context context, Bitmap scaledBitmaps[], Bitmap backup) {
        mScaledBitmaps = scaledBitmaps;
        mLevelCount = mScaledBitmaps.length;
        mBackupTexture = new BitmapTexture(backup);

        mImageWidth = scaledBitmaps[0].getWidth();
        mImageHeight = scaledBitmaps[0].getHeight();
        setPosition(mImageWidth / 2, mImageHeight / 2, 0.5f);

        mScaleDetector = new ScaleGestureDetector(context, new MyScaleListener());
        mGestureDetector = new GestureDetector(context, new MyGestureListener());
    }

    @Override
    protected boolean onTouch(MotionEvent event) {
        mGestureDetector.onTouchEvent(event);
        mScaleDetector.onTouchEvent(event);
        return true;
    }

    private static int ceilLog2(float value) {
        int i;
        for (i = 0; i < 30; i++) {
            if ((1 << i) > value) break;
        }
        return i;
    }

    @Override
    protected void onLayout(boolean changeSize, int l, int t, int r, int b) {
        if (changeSize) layoutTiles(mCenterX, mCenterY, mScale);
    }

    // Prepare the tiles we want to use for display.
    //
    // 1. Decide the tile level we want to use for display.
    // 2. Decide the tile levels we want to keep as texture (in addition to
    //    the one we use for display).
    // 3. Recycle unused tiles.
    // 4. Activate the tiles we want.
    private void layoutTiles(int centerX, int centerY, float scale) {

        // The width and height of this view.
        int width = getWidth();
        int height = getHeight();

        // The tile levels we want to keep as texture is in the range
        // [fromLevel, endLevel).
        int fromLevel;
        int endLevel;

        // We want to use a texture smaller than the display size to avoid
        // displaying artifacts.
        mLevel = Util.clamp(ceilLog2(1f / scale), 0, mLevelCount);

        // We want to keep one more tile level as texture in addition to what
        // we use for display. So it can be faster when the scale moves to the
        // next level. We choose a level closer to the current scale.
        if (mLevel != mLevelCount) {
            Rect range = mTileRange;
            getRange(range, centerX, centerY, mLevel, scale);
            mOffsetX = Math.round(width / 2f + (range.left - centerX) * scale);
            mOffsetY = Math.round(height / 2f + (range.top - centerY) * scale);
            fromLevel = scale * (1 << mLevel) > 1.5f ? mLevel - 1 : mLevel;
        } else {
            mOffsetX = Math.round(width / 2f - centerX * scale);
            mOffsetY = Math.round(height / 2f - centerY * scale);
            // If mLevel == mLevelCount, we will use the backup texture for
            // display, so keep two smallest levels of tiles.
            fromLevel = mLevel - 2;
        }

        fromLevel = Math.max(fromLevel, 0);
        endLevel = Math.min(fromLevel + 2, mLevelCount);
        Rect range[] = mActiveRange;
        for (int i = fromLevel; i < endLevel; ++i) {
            getRange(range[i - fromLevel], centerX, centerY, i);
        }

        // Recycle unused tiles: if the level of the active tile is outside the
        // range [fromLevel, endLevel) or not in the visible range.
        Iterator<Map.Entry<Long, Tile>>
                iter = mActiveTiles.entrySet().iterator();
        while (iter.hasNext()) {
            Tile tile = iter.next().getValue();
            int level = tile.mTileLevel;
            if (level < fromLevel || level >= endLevel
                    || !range[level - fromLevel].contains(tile.mX, tile.mY)) {
                iter.remove();
                recycleTile(tile);
            }
        }

        for (int i = fromLevel; i < endLevel; ++i) {
            int size = TILE_SIZE << i;
            Rect r = range[i - fromLevel];
            for (int y = r.top, bottom = r.bottom; y < bottom; y += size) {
                for (int x = r.left, right = r.right; x < right; x += size) {
                    activateTile(x, y, i);
                }
            }
        }
        mUploadIter = mActiveTiles.values().iterator();
    }

    private void getRange(Rect out, int cX, int cY, int level) {
        getRange(out, cX, cY, level, 1f / (1 << (level + 1)));
    }

    // If the bitmap is scaled by the given factor "scale", return the
    // rectangle containing visible range. The left-top coordinate returned is
    // aligned to the tile boundary.
    //
    // (cX, cY) is the point on the original bitmap which will be put in the
    // center of the ImageViewer.
    private void getRange(Rect out, int cX, int cY, int level, float scale) {
        int width = getWidth();
        int height = getHeight();

        int left = Math.round(cX - width / (2f * scale));
        int top = Math.round(cY - height / (2f * scale));
        int right = (int) Math.ceil(left + width / scale);
        int bottom = (int) Math.ceil(top + height / scale);

        // align the rectangle to tile boundary
        int size = TILE_SIZE << level;
        left = Math.max(0, size * (left / size));
        top = Math.max(0, size * (top / size));
        right = Math.min(mImageWidth, right);
        bottom = Math.min(mImageHeight, bottom);

        out.set(left, top, right, bottom);
    }

    public void setPosition(int centerX, int centerY, float scale) {
        if (centerX == mCenterX && centerY == mCenterY && scale == mScale) {
            return;
        }

        mCenterX = centerX;
        mCenterY = centerY;
        mScale = scale;

        layoutTiles(centerX, centerY, scale);
        invalidate();
    }

    public void close() {
        mUploadIter = null;
        GLCanvas canvas = getGLRootView().getCanvas();
        for (Tile texture : mActiveTiles.values()) {
            canvas.unloadTexture(texture);
            texture.recycle();
        }
        mActiveTiles.clear();
        freeRecycledTile(canvas);
    }

    @Override
    protected void render(GLCanvas canvas) {
        mUploadQuota = UPLOAD_LIMIT;
        mRenderComplete = true;

        int level = mLevel;
        if (level == mLevelCount) {
            mBackupTexture.draw(canvas, mOffsetX, mOffsetY,
                    (int) (mImageWidth * mScale), (int) (mImageHeight * mScale));
        } else {
            int size = (TILE_SIZE << level);
            float length = size * mScale;
            Rect r = mTileRange;

            for (int ty = r.top, i = 0; ty < r.bottom; ty += size, i++) {
                float y = mOffsetY + i * length;
                for (int tx = r.left, j = 0; tx < r.right; tx += size, j++) {
                    float x = mOffsetX + j * length;
                    Tile tile = getTile(tx, ty, level);
                    tile.drawTile(canvas, x, y, length);
                }
            }
        }
        if (mRenderComplete) {
            if (mUploadIter.hasNext() && !mUploader.mActive) {
                mUploader.mActive = true;
                getGLRootView().addOnGLIdleListener(mUploader);
            }
        } else {
            invalidate();
        }
    }

    private Tile obtainTile(int x, int y, int level) {
        Tile tile;
        if (mRecycledHead != null) {
            tile = mRecycledHead;
            mRecycledHead = tile.mNextFree;
            tile.update(x, y, level);
        } else {
            tile = new Tile(x, y, level);
        }
        return tile;
    }

    private void recycleTile(Tile tile) {
        tile.mNextFree = mRecycledHead;
        mRecycledHead = tile;
    }

    private void freeRecycledTile(GLCanvas canvas) {
        Tile tile = mRecycledHead;
        while (tile != null) {
            canvas.unloadTexture(tile);
            tile.recycle();
            tile = tile.mNextFree;
        }
        mRecycledHead = null;
    }

    private void activateTile(int x, int y, int level) {
        Long key = makeTileKey(x, y, level);
        Tile tile = mActiveTiles.get(key);
        if (tile != null) return;
        tile = obtainTile(x, y, level);
        mActiveTiles.put(key, tile);
    }

    private Tile getTile(int x, int y, int level) {
        return mActiveTiles.get(makeTileKey(x, y, level));
    }

    public static Long makeTileKey(int x, int y, int level) {
        long result = x;
        result = (result << 16) | y;
        result = (result << 16) | level;
        return Long.valueOf(result);
    }

    // TODO: avoid drawing the unused part of the textures.
    static boolean drawTile(
            Tile tile, GLCanvas canvas, RectF source, RectF target) {
        while (true) {
            if (tile.isContentValid(canvas)) {
                // offset source rectangle for the texture border.
                source.offset(TILE_BORDER, TILE_BORDER);
                canvas.drawTexture(tile, source, target);
                return true;
            }

            // Parent can be divided to four quads and tile is one of the four.
            Tile parent = tile.getParentTile();
            if (parent == null) return false;
            if (tile.mX == parent.mX) {
                source.left /= 2f;
                source.right /= 2f;
            } else {
                source.left = (TILE_SIZE + source.left) / 2f;
                source.right = (TILE_SIZE + source.right) / 2f;
            }
            if (tile.mY == parent.mY) {
                source.top /= 2f;
                source.bottom /= 2f;
            } else {
                source.top = (TILE_SIZE + source.top) / 2f;
                source.bottom = (TILE_SIZE + source.bottom) / 2f;
            }
            tile = parent;
        }
    }

    private class Uploader implements GLRootView.OnGLIdleListener {

        protected boolean mActive;

        public boolean onGLIdle(GLRootView root) {
            int quota = UPLOAD_LIMIT;
            GLCanvas canvas = root.getCanvas();

            if (mUploadIter == null) return false;
            Iterator<Tile> iter = mUploadIter;
            while (iter.hasNext() && quota > 0) {
                Tile tile = iter.next();
                if (!tile.isContentValid(canvas)) {
                    tile.updateContent(canvas);
                    Log.v(TAG, String.format(
                            "update tile in background: %s %s %s",
                            tile.mX / TILE_SIZE, tile.mY / TILE_SIZE,
                            tile.mTileLevel));
                    --quota;
                }
            }
            mActive = iter.hasNext();
            return mActive;
        }
    }

    private class Tile extends UploadedTexture {
        int mX;
        int mY;
        int mTileLevel;
        Tile mNextFree;

        public Tile(int x, int y, int level) {
            mX = x;
            mY = y;
            mTileLevel = level;
        }

        @Override
        protected void onFreeBitmap(Bitmap bitmap) {
            bitmap.recycle();
        }

        @Override
        protected Bitmap onGetBitmap() {
            int level = mTileLevel;
            Bitmap source = mScaledBitmaps[level];
            Bitmap bitmap = Bitmap.createBitmap(TILE_SIZE + 2 * TILE_BORDER,
                    TILE_SIZE + 2 * TILE_BORDER,
                    source.hasAlpha() ? Config.ARGB_8888 : Config.RGB_565);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawBitmap(source, -(mX >> level) + TILE_BORDER,
                    -(mY >> level) + TILE_BORDER, null);
            return bitmap;
        }

        public void update(int x, int y, int level) {
            mX = x;
            mY = y;
            mTileLevel = level;
            invalidateContent();
        }

        public void drawTile(GLCanvas canvas, float x, float y, float length) {
            RectF source = mSourceRect;
            RectF target = mTargetRect;
            target.set(x, y, x + length, y + length);
            source.set(0, 0, TILE_SIZE, TILE_SIZE);
            drawTile(canvas, source, target);
        }

        public Tile getParentTile() {
            if (mTileLevel + 1 == mLevelCount) return null;
            int size = TILE_SIZE << (mTileLevel + 1);
            int x = size * (mX / size);
            int y = size * (mY / size);
            return getTile(x, y, mTileLevel + 1);
        }

        public void drawTile(GLCanvas canvas, RectF source, RectF target) {
            if (!isContentValid(canvas)) {
                if (mUploadQuota > 0) {
                    --mUploadQuota;
                    updateContent(canvas);
                } else {
                    mRenderComplete = false;
                }
            }
            if (!ImageViewer.drawTile(this, canvas, source, target)) {
                BitmapTexture backup = mBackupTexture;
                int width = mImageWidth;
                int height = mImageHeight;
                float scaleX = (float) backup.getWidth() / width;
                float scaleY = (float) backup.getHeight() / height;
                int size = TILE_SIZE << mTileLevel;

                source.set(mX * scaleX, mY * scaleY, (mX + size) * scaleX,
                        (mY + size) * scaleY);

                canvas.drawTexture(backup, source, target);
            }
        }
    }

    private class MyGestureListener
            extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(
                MotionEvent e1, MotionEvent e2, float dx, float dy) {
            setPosition((int) (mCenterX + dx / mScale),
                    (int) (mCenterY + dy / mScale), mScale);
            return true;
        }
    }

    private class MyScaleListener
            extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        // The offsets of the focus point to the center of the image on the
        // image domain.
        private float mPrevOffsetX;
        private float mPrevOffsetY;

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scale = detector.getScaleFactor();
            if (Float.isNaN(scale) || Float.isInfinite(scale)) return true;
            scale = Util.clamp(scale * mScale, 0.02f, 2);

            // The focus point should keep this position on the ImageView.
            // So, mCenterX + mPrevOffsetX = mCenterX' + offsetX.
            // mCenterY + mPrevOffsetY = mCenterY' + offsetY.
            float offsetX = (detector.getFocusX() - getWidth() / 2) / scale;
            float offsetY = (detector.getFocusY() - getHeight() / 2) / scale;
            setPosition((int) (mCenterX - (offsetX - mPrevOffsetX) + 0.5),
                    (int) (mCenterY - (offsetY - mPrevOffsetY) + 0.5), scale);
            mPrevOffsetX = offsetX;
            mPrevOffsetY = offsetY;

            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mPrevOffsetX = (detector.getFocusX() - getWidth() / 2) / mScale;
            mPrevOffsetY = (detector.getFocusY() - getHeight() / 2) / mScale;
            return true;
        }
    }
}

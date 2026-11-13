package app.anancy.player;

// ============================================================================
//  A follower TV streaming a show from its leader's /media. The leader seals
//  the file in 64 KiB blocks under a key only this request can derive (see
//  PairCrypto.MEDIA_BLOCK), so this opens each block, checks it is the block
//  it should be, and hands ExoPlayer plain bytes from the position it asked
//  for. A block that doesn't open is an error, never a picture.
// ============================================================================

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;

import app.anancy.Pairing;
import app.anancy.net.PairCrypto;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@androidx.media3.common.util.UnstableApi
final class SealedMediaDataSource extends BaseDataSource {
    private final Context ctx;
    private Uri uri;
    private HttpURLConnection conn;
    private InputStream in;
    private boolean opened;
    private byte[] sk;
    private long total, index, skip, remaining;
    private byte[] plain;
    private int pPos, pLen;
    private final byte[] sealed = new byte[PairCrypto.MEDIA_BLOCK + PairCrypto.BLOCK_TAG];

    SealedMediaDataSource(Context ctx) { super(true); this.ctx = ctx.getApplicationContext(); }

    @Override public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        if (!"/media".equals(spec.uri.getPath())) throw new IOException("only a TV's /media is streamed over HTTP");
        transferInitializing(spec);
        long pos = spec.position, want = spec.length;
        conn = (HttpURLConnection) new URL(spec.uri.toString()).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Range", "bytes=" + pos + "-" + (want == C.LENGTH_UNSET ? "" : String.valueOf(pos + want - 1)));
        Pairing.sign(ctx, conn);
        int code = conn.getResponseCode();
        sk = Pairing.mediaStreamKey(ctx, conn);
        String len = conn.getHeaderField("X-BST-Len"), blk = conn.getHeaderField("X-BST-Block");
        if ((code != 200 && code != 206) || sk == null || len == null || blk == null) {
            closeQuietly();
            throw new IOException("leader refused the stream (" + code + ")");
        }
        try { total = Long.parseLong(len.trim()); index = Long.parseLong(blk.trim()); }
        catch (NumberFormatException e) { closeQuietly(); throw new IOException("bad stream header"); }
        if (pos > total) { closeQuietly(); throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE); }
        if (pos < total && pos / PairCrypto.MEDIA_BLOCK != index) { closeQuietly(); throw new IOException("stream starts at the wrong block"); }
        skip = pos - index * PairCrypto.MEDIA_BLOCK;
        remaining = want == C.LENGTH_UNSET ? total - pos : Math.min(want, total - pos);
        plain = null; pPos = pLen = 0;
        in = conn.getInputStream();
        opened = true;
        transferStarted(spec);
        return remaining;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining <= 0) return C.RESULT_END_OF_INPUT;
        if (pPos >= pLen) nextBlock();
        int n = (int) Math.min(Math.min(length, pLen - pPos), remaining);
        System.arraycopy(plain, pPos, buffer, offset, n);
        pPos += n; remaining -= n;
        bytesTransferred(n);
        return n;
    }

    private void nextBlock() throws IOException {
        int need = PairCrypto.blockLen(total, index) + PairCrypto.BLOCK_TAG, got = 0;
        while (got < need) {
            int r = in.read(sealed, got, need - got);
            if (r < 0) throw new IOException("stream ended early");
            got += r;
        }
        plain = PairCrypto.openBlock(sk, total, index, sealed, 0, need);
        if (plain == null) throw new IOException("a block of the stream failed its seal");
        index++;
        pPos = (int) skip; skip = 0;
        pLen = plain.length;
    }

    @Override public Uri getUri() { return uri; }

    @Override public void close() {
        closeQuietly();
        if (opened) { opened = false; transferEnded(); }
    }

    private void closeQuietly() {
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        in = null; conn = null;
    }
}

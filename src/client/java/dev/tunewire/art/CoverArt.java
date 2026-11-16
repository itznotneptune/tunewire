package dev.tunewire.art;

import com.mojang.blaze3d.platform.NativeImage;
import dev.tunewire.TunewireClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Album art as GUI textures. Spotify serves JPEGs, which are decoded with ImageIO and pre-scaled to the
 * two sizes drawn on screen (the HUD and the player), so nearest-neighbour sampling still looks clean.
 */
public final class CoverArt {
    public static final CoverArt INSTANCE = new CoverArt();
    public static final int SMALL = 64;
    public static final int LARGE = 192;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile String url = "";
    private volatile Identifier small;
    private volatile Identifier large;
    private int counter;

    private CoverArt() {}

    /** Texture ids for the current cover, or null while none is loaded. */
    public Identifier small() {
        return small;
    }

    public Identifier large() {
        return large;
    }

    public void clear() {
        url = "";
        Minecraft.getInstance().execute(() -> swap(null, null));
    }

    public void request(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            clear();
            return;
        }
        if (imageUrl.equals(url)) return;
        url = imageUrl;
        HttpRequest req = HttpRequest.newBuilder(URI.create(imageUrl)).timeout(Duration.ofSeconds(15)).GET().build();
        dev.tunewire.Perf.request();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenAccept(res -> {
            if (res.statusCode() != 200 || !imageUrl.equals(url)) return;
            try {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(res.body()));
                if (src == null) return;
                NativeImage s = toNative(scale(src, SMALL));
                NativeImage l = toNative(scale(src, LARGE));
                Minecraft.getInstance().execute(() -> {
                    if (!imageUrl.equals(url)) {
                        s.close();
                        l.close();
                        return;
                    }
                    int n = ++counter;
                    Identifier sid = register("cover_s" + n, s);
                    Identifier lid = register("cover_l" + n, l);
                    swap(sid, lid);
                });
            } catch (Exception e) {
                TunewireClient.LOG.warn("Could not decode cover art", e);
            }
        }).exceptionally(e -> {
            TunewireClient.LOG.debug("Cover art download failed", e);
            return null;
        });
    }

    /** Render thread only. */
    private void swap(Identifier s, Identifier l) {
        Identifier oldS = small, oldL = large;
        small = s;
        large = l;
        var tm = Minecraft.getInstance().getTextureManager();
        if (oldS != null) tm.release(oldS);
        if (oldL != null) tm.release(oldL);
    }

    private static Identifier register(String path, NativeImage img) {
        Identifier id = Identifier.fromNamespaceAndPath(TunewireClient.MOD_ID, path);
        DynamicTexture tex = new DynamicTexture(() -> "tunewire " + path, img);
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return id;
    }

    /** Centre-crops to a square, then halves repeatedly before the final bilinear step to avoid aliasing. */
    private static BufferedImage scale(BufferedImage src, int size) {
        int side = Math.min(src.getWidth(), src.getHeight());
        BufferedImage img = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        int cur = side;
        while (cur / 2 >= size) {
            cur /= 2;
            img = resize(img, cur);
        }
        return cur == size ? img : resize(img, size);
    }

    private static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static NativeImage toNative(BufferedImage img) {
        NativeImage n = new NativeImage(img.getWidth(), img.getHeight(), false);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                n.setPixel(x, y, img.getRGB(x, y) | 0xFF000000); // ARGB, forced opaque
            }
        }
        return n;
    }
}

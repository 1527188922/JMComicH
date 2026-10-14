import com.alibaba.fastjson.JSONObject;
import com.haruhi.botserver.features.jmcomic.client.model.Album;
import com.haruhi.botserver.features.jmcomic.client.model.Chapter;
import com.haruhi.botserver.features.jmcomic.client.model.Series;
import com.haruhi.botserver.features.jmcomic.service.JmcomicService;
import com.haruhi.botserver.shared.util.FileUtil;
import org.apache.commons.codec.digest.DigestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 从参考实现（haruhibot-server）生成 Android 工程的黄金用例数据。
 * 不修改参考工程源码：全部通过反射/公开方法调用编译产物。
 */
public class GoldenGen {

    static final String APP_TOKEN_SECRET = "18comicAPP";
    static final String APP_TOKEN_SECRET_2 = "18comicAPPContent";
    static final String APP_DATA_SECRET = "185Hcomic3PAPP7R";
    static final String APP_VERSION = "2.0.13";

    static StringBuilder out = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : ".");
        Files.createDirectories(dir);

        JmcomicService svc = new JmcomicService();
        Method stitchImg = JmcomicService.class.getDeclaredMethod("stitchImg", BufferedImage.class, int.class);
        stitchImg.setAccessible(true);
        Method decryptData = JmcomicService.class.getDeclaredMethod("decryptData", long.class, String.class);
        decryptData.setAccessible(true);

        long[] tsList = {1700000000L, 1735689600L, 1893456000L};

        // ---------- G1 JM 签名 ----------
        out.append("# G1 JM token signature (secret=18comicAPP, version=2.0.13)\n");
        for (long ts : tsList) {
            String token = DigestUtils.md5Hex(ts + APP_TOKEN_SECRET);
            String tokenContent = DigestUtils.md5Hex(ts + APP_TOKEN_SECRET_2);
            out.append("G1|").append(ts).append('|').append(token).append('|').append(ts).append(',').append(APP_VERSION).append('\n');
            out.append("G1C|").append(ts).append('|').append(tokenContent).append('\n');
        }

        // ---------- G2 AES 密钥与解密 ----------
        out.append("# G2 AES key = md5(ts + 185Hcomic3PAPP7R) hex string as ASCII bytes (32 bytes), AES/ECB/PKCS5Padding\n");
        for (long ts : tsList) {
            String keyHex = DigestUtils.md5Hex(ts + APP_DATA_SECRET);
            out.append("G2KEY|").append(ts).append('|').append(keyHex).append('|').append(keyHex.getBytes(StandardCharsets.US_ASCII).length).append('\n');
        }
        // 用参考实现同款密钥加密一段明文，再用参考实现的 decryptData 解回来，作为解密黄金对
        String[] plaintexts = {
                "{\"id\":480854,\"name\":\"テスト本子\",\"series\":[]}",
                "中文标题 + emoji \uD83D\uDE00 + ascii",
                ""
        };
        for (int i = 0; i < plaintexts.length; i++) {
            long ts = 1700000000L + i;
            String keyHex = DigestUtils.md5Hex(ts + APP_DATA_SECRET);
            Cipher enc = Cipher.getInstance("AES/ECB/PKCS5Padding");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyHex.getBytes(StandardCharsets.US_ASCII), "AES"));
            String cipherText = Base64.getEncoder().encodeToString(enc.doFinal(plaintexts[i].getBytes(StandardCharsets.UTF_8)));
            String decrypted = (String) decryptData.invoke(svc, ts, cipherText);
            if (!decrypted.equals(plaintexts[i])) {
                throw new IllegalStateException("reference decrypt mismatch at " + i);
            }
            out.append("G2|").append(ts).append('|').append(cipherText).append('|')
                    .append(b64(plaintexts[i]))
                    .append('\n');
        }

        // ---------- G3 calculateBlockNum ----------
        out.append("# G3 calculateBlockNum(scrambleId, chapterId, filenameWithoutExt)\n");
        long[] scrambleIds = {0L, 220980L, 268850L};
        long[] chapterIds = {0L, 1L, 100000L, 220979L, 220980L, 220981L, 268849L, 268850L, 268851L, 400000L, 421925L, 421926L, 421927L, 999999L, 1234567L};
        String[] names = {"00001", "1", "abc", "", "00010", "第1页"};
        for (long sid : scrambleIds) {
            for (long cid : chapterIds) {
                for (String n : names) {
                    int block = svc.calculateBlockNum(sid, cid, n);
                    out.append("G3|").append(sid).append('|').append(cid).append('|').append(n).append('|').append(block).append('\n');
                }
            }
        }

        // ---------- G4 sanitizeFileName ----------
        out.append("# G4 FileUtil.sanitizeFileName(name, fallback, maxBytes)\n");
        Object[][] cases = {
                {"正常标题", "480854", 150},
                {"a\\b/c:d*e?f\"g<h>i|j", "480854", 150},
                {"只有非法字符???", "480854", 150},
                {"  前后空白  ", "480854", 150},
                {"结尾是点...", "480854", 150},
                {"CON", "480854", 150},
                {"con", "480854", 150},
                {"com1", "480854", 150},
                {"NUL", "480854", 150},
                {"a\u0000b\u0001c", "480854", 150},
                {"这是一个非常长的中文标题用于测试按字节截断的行为以及字符边界处理是否正确", "480854", 30},
                {"这是一个非常长的中文标题用于测试按字节截断的行为以及字符边界处理是否正确", "480854", 0},
                {"😀😀😀😀😀😀😀😀", "480854", 10},
                {"", "480854", 150},
                {"   ", "480854", 150},
                {"...", "480854", 150},
                {"标题.", "480854", 150},
                {"标题 ", "480854", 150},
                {"A\u00A0B", "480854", 150},
                {"Café — naïve ünïcødé", "480854", 150},
                {"\uD83D\uDE00emoji前缀", "480854", 8},
                {"name", "fallback", 1},
                {"name", "fallback", 2},
        };
        for (Object[] c : cases) {
            String name = (String) c[0];
            String fallback = (String) c[1];
            int max = (Integer) c[2];
            String result = FileUtil.sanitizeFileName(name, fallback, max);
            out.append("G4|").append(b64(name)).append('|').append(b64(fallback)).append('|').append(max)
                    .append('|').append(b64(result)).append('\n');
        }

        // buildAlbumFolderName 的纯逻辑部分（后缀不参与截断）
        out.append("# G4B folderName = sanitizeFileName(name, aid, maxBytes - len(\"_JM\"+aid)) + \"_JM\" + aid\n");
        Object[][] folderCases = {
                {"正常标题", "480854", 150},
                {"a\\b/c:d*e?f\"g<h>i|j", "480854", 150},
                {"这是一个非常长的中文标题用于测试按字节截断的行为以及字符边界处理是否正确", "480854", 40},
                {"???", "480854", 150},
        };
        for (Object[] c : folderCases) {
            String name = (String) c[0];
            String aid = (String) c[1];
            int maxBytes = (Integer) c[2];
            String suffix = "_JM" + aid;
            String base = FileUtil.sanitizeFileName(name, aid, maxBytes - suffix.getBytes(StandardCharsets.UTF_8).length);
            out.append("G4B|").append(b64(name)).append('|').append(aid).append('|').append(maxBytes)
                    .append('|').append(b64(base + suffix)).append('\n');
        }

        // ---------- G5 排序 ----------
        out.append("# G5 sortFolders/sortFiles semantics (extract chapter/image number)\n");
        String[] folders = {"第10话", "第2话", "第1话", "番外", "第1话_extra", "封面"};
        List<String> folderList = new ArrayList<>(List.of(folders));
        folderList.sort((f1, f2) -> Integer.compare(chapterNo(f1), chapterNo(f2)));
        out.append("G5F|").append(String.join(",", folderList)).append('\n');
        String[] images = {"10", "2", "1", "003", "cover", "1a", ""};
        List<String> imageList = new ArrayList<>(List.of(images));
        List<String> sortedImages = JmcomicService.sortImageFiles(imageList);
        out.append("G5I|").append(String.join(",", sortedImages)).append('\n');

        // ---------- G7 解扰 ----------
        out.append("# G7 stitchImg golden images\n");
        int[][] shapes = {
                {16, 40},   // height % blockNum == 0
                {16, 43},   // remainder
                {1, 7},
                {7, 1},
                {64, 130},
        };
        int[] blockNums = {2, 4, 8, 10, 22};
        for (int[] shape : shapes) {
            BufferedImage src = makeTestImage(shape[0], shape[1]);
            for (int bn : blockNums) {
                String id = shape[0] + "x" + shape[1] + "_b" + bn;
                File srcFile = dir.resolve("descramble_src_" + id + ".png").toFile();
                File dstFile = dir.resolve("descramble_dst_" + id + ".png").toFile();
                ImageIO.write(src, "png", srcFile);
                BufferedImage dst = (BufferedImage) stitchImg.invoke(svc, src, bn);
                ImageIO.write(dst, "png", dstFile);
                out.append("G7|").append(shape[0]).append('|').append(shape[1]).append('|').append(bn)
                        .append('|').append(pixelDigest(dst)).append('\n');
            }
        }

        // ---------- G8 响应样本字段名 ----------
        out.append("# G8 model json field names (fastjson serialization of reference models)\n");
        Album album = new Album();
        album.setId(480854L);
        album.setName("标题");
        album.setAddTime("2023-01-01");
        album.setSeriesId("123");
        album.setCommentTotal("7");
        album.setAuthor(List.of("作者A"));
        album.setTags(List.of("标签1", "标签2"));
        Series s = new Series();
        s.setId("500001");
        s.setName("第1话");
        s.setSort("1");
        s.setTitle("第1话");
        album.setSeries(List.of(s));
        album.setAlbumFolderName("标题_JM480854");
        out.append("G8A|").append(JSONObject.toJSONString(album)).append('\n');
        Chapter chapter = new Chapter();
        chapter.setId(500001L);
        chapter.setName("第1话");
        chapter.setImages(List.of("00001.webp", "00002.webp"));
        chapter.setSeriesId("123");
        out.append("G8C|").append(JSONObject.toJSONString(chapter)).append('\n');

        // ---------- G9 参考实现的常量 ----------
        out.append("# G9 constants reflected from reference class\n");
        for (String fieldName : new String[]{"DEFAULT_API_DOMAIN", "IMAGE_DOMAIN", "COVER_DOMAIN", "JM_DEFAULT_PASSWORD", "SEARCH_PAGE_SIZE"}) {
            Field f = JmcomicService.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            out.append("G9|").append(fieldName).append('|').append(f.get(null)).append('\n');
        }

        Files.write(dir.resolve("golden.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + dir.resolve("golden.txt").toAbsolutePath());
    }

    static int chapterNo(String folderName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("第(\\d+)话").matcher(folderName.split("_")[0]);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static String b64(String s) {
        if (s == null) {
            return "-";
        }
        try {
            // URL 编码而不是 base64：空串可表达、可读、无填充歧义。
            // 空格用 %20 而不是 '+'，避免解码侧对 '+' 的解释产生歧义。
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static BufferedImage makeTestImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0xFF;
                int r = (x * 7 + y * 13) & 0xFF;
                int g = (x * 31 + y * 3) & 0xFF;
                int b = (x * 5 + y * 29) & 0xFF;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    static String pixelDigest(BufferedImage img) {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        int w = img.getWidth();
        int h = img.getHeight();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(w * 4);
        for (int y = 0; y < h; y++) {
            buf.clear();
            for (int x = 0; x < w; x++) {
                buf.putInt(img.getRGB(x, y));
            }
            md.update(buf.array());
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

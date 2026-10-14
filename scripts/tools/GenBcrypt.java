import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 生成/校验 BCrypt 密码哈希（用于初始化数据与排障）
 * 用法: java -cp "<classpath>" GenBcrypt.java <明文密码>
 */
public class GenBcrypt {
    public static void main(String[] args) {
        String raw = args.length > 0 ? args[0] : "Admin@123";
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode(raw);
        System.out.println("PASSWORD=" + raw);
        System.out.println("HASH=" + hash);
        System.out.println("MATCH_SELF=" + encoder.matches(raw, hash));
        if (args.length > 1) {
            System.out.println("MATCH_GIVEN=" + encoder.matches(raw, args[1]));
        }
    }
}

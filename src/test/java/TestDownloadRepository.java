import fr.kage.nexus3.DownloadRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.Test;


public class TestDownloadRepository {
    @Test
    public void f(){
        String url = "http://192.168.7.205:8081/";
        String repoId = "maven-snapshots";
        String downloadPath = "D:\\leizhen\\国票\\nexus迁移\\7.205\\maven-snapshots";
        new DownloadRepository(url, repoId, downloadPath).start();
    }
}

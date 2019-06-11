package fr.kage.nexus3;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;


public class DownloadRepository implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(DownloadRepository.class);

	private final String url;
	private final String repositoryId;
	private Path downloadPath;

	private RestTemplate restTemplate;
	private ExecutorService executorService;

	private AtomicLong assetProcessed = new AtomicLong();
	private AtomicLong assetFound = new AtomicLong();


	public DownloadRepository(String url, String repositoryId, String downloadPath) {
		this.url = requireNonNull(url);
		this.repositoryId = requireNonNull(repositoryId);
		this.downloadPath = downloadPath == null ? null : Paths.get(downloadPath);
	}


	public void start() {
		try {
			if (downloadPath == null)
				downloadPath = Files.createTempDirectory("nexus3");
			else{
				if(!downloadPath.toFile().exists()){
					LOGGER.info("本地保存文件夹不存在，先创建:{}", downloadPath.toString());
					downloadPath.toFile().mkdirs();
				}
				else{
					LOGGER.error("源文件夹已经存在:{}", downloadPath.toString());
					return;
				}
			}

			LOGGER.info("Starting download of Nexus 3 repository in local directory {}", downloadPath);
			executorService = Executors.newFixedThreadPool(10);
			restTemplate = new RestTemplate();

			Future future = executorService.submit(this);
			while(!future.isDone()){
				try {
					Thread.currentThread().sleep(10000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			//executorService.awaitTermination(1, TimeUnit.DAYS);
		}
		catch (IOException e) {
			LOGGER.error("Unable to create/use directory for local data: " + downloadPath);
		}

	}


	@Override
	public void run() {
		checkState(executorService != null, "Executor not initialized");
		executorService.submit(new DownloadAssetsTask(null));
	}


	void notifyProgress() {
		LOGGER.info("Downloaded {} assets on {} found", assetProcessed.get(), assetFound.get());
	}


	private class DownloadAssetsTask implements Runnable {

		private String continuationToken;


		public DownloadAssetsTask(String continuationToken) {
			this.continuationToken = continuationToken;
		}


		@Override
		public void run() {
			LOGGER.info("Retrieving some assets");
			UriComponentsBuilder getAssets = UriComponentsBuilder.fromHttpUrl(url)
					.pathSegment("service", "rest", "v1", "assets")
					.queryParam("repository", repositoryId);
			if (continuationToken != null)
				getAssets = getAssets.queryParam("continuationToken", continuationToken);

			final ResponseEntity<Assets> assetsEntity = restTemplate.getForEntity(getAssets.build().toUri(), Assets.class);
			final Assets assets = assetsEntity.getBody();
			if (assets.getContinuationToken() != null) {
				executorService.submit(new DownloadAssetsTask(assets.getContinuationToken()));
			}

			assetFound.addAndGet(assets.getItems().size());
			notifyProgress();
			assets.getItems().forEach(item -> executorService.submit(new DownloadItemTask(item)));

			if(assetFound.get() == assetProcessed.get()){
				LOGGER.info("Download complete.");
				return;
			}
		}
	}


	private class DownloadItemTask implements Runnable {

		private Item item;


		public DownloadItemTask(Item item) {
			this.item = item;
		}


		@Override
		public void run() {
			LOGGER.info("Downloading asset <{}>", item.getDownloadUrl());

			try {
				Path assetPath = downloadPath.resolve(item.getPath());
				Files.createDirectories(assetPath.getParent());
				final URI downloadUri = URI.create(item.getDownloadUrl());
				int tryCount = 1;
				while (tryCount <= 3) {
					try (InputStream assetStream = downloadUri.toURL().openStream()) {
						Files.copy(assetStream, assetPath);
						final HashCode hash = com.google.common.io.Files.asByteSource(assetPath.toFile()).hash(Hashing.sha1());
						if (Objects.equals(hash.toString(), item.getChecksum().getSha1()))
							break;
						tryCount++;
						LOGGER.info("Download failed, retrying");
					}
				}
				assetProcessed.incrementAndGet();
				notifyProgress();
			}
			catch (IOException e) {
				LOGGER.error("Failed to download asset <" + item.getDownloadUrl() + ">", e);
			}
		}
	}
}

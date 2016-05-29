package org.lanatec.devtools;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;

import javax.annotation.PostConstruct;

import lombok.AllArgsConstructor;
import lombok.Data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class BrowserSync implements Runnable {

	@Autowired
	private SimpMessagingTemplate template;

	@Value("${spring.devtools.browsersync.directories}")
	private String directoriesWatch;

	@Value("${spring.devtools.browsersync.enabled}")
	private boolean watcherEnabled;

	@PostConstruct
	public void initSyncThread() {
		new Thread(this).start();
	}

	@Override
	public void run() {

		WatchService watcher = null;
		try {
			watcher = FileSystems.getDefault()
					.newWatchService();
			final WatchService watcherReady = watcher;

			String[] dirArr = directoriesWatch.split(",");
			for (String dir : dirArr) {
				Path tmpDir = Paths.get(dir.trim()).toAbsolutePath().normalize();
				if(tmpDir.toFile().isDirectory()) {
					Files.walkFileTree(tmpDir, new SimpleFileVisitor<Path>() {
			            @Override
			            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
			                throws IOException
			            {
			            	dir.register(watcherReady, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
			                return FileVisitResult.CONTINUE;
			            }
			        });
				} else {
					tmpDir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
				}
			}
		} catch (IOException e) {
			return;
		}

		while (watcherEnabled) {
			WatchKey key;
			try {
				key = watcher.take();
			} catch (InterruptedException x) {
				return;
			}

			for (WatchEvent<?> event : key.pollEvents()) {
				WatchEvent.Kind<?> kind = event.kind();

				if (kind == OVERFLOW) {
					continue;
				}

				WatchEvent<Path> ev = (WatchEvent<Path>) event;
				Path filename = ev.context();

				template.convertAndSend("/wsdevtools/filesync", new EventBean(filename.toAbsolutePath().toString(), kind.toString()));
			}

			// Reset the key -- this step is critical if you want to
			// receive further watch events. If the key is no longer valid,
			// the directory is inaccessible so exit the loop.
			boolean valid = key.reset();
			if (!valid) {
				break;
			}
		}
	}

	@Data
	@AllArgsConstructor
	public class EventBean {
		String filename;
		String event;
	}
}
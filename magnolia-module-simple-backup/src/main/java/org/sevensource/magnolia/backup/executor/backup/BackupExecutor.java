package org.sevensource.magnolia.backup.executor.backup;

import info.magnolia.context.SystemContext;
import info.magnolia.importexport.command.JcrExportCommand;
import info.magnolia.importexport.command.JcrExportCommand.Compression;
import info.magnolia.importexport.contenthandler.XmlContentHandlerFactory;
import info.magnolia.importexport.filters.NamespaceFilter;
import info.magnolia.jcr.decoration.ContentDecorator;
import info.magnolia.jcr.util.NodeTypes;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.jackrabbit.commons.xml.SystemViewExporter;
import org.sevensource.magnolia.backup.configuration.SimpleBackupWorkspaceConfiguration;
import org.sevensource.magnolia.backup.descriptor.SimpleBackupJobFileDescriptor;
import org.sevensource.magnolia.backup.executor.backup.filter.ExcludeNodePathsAndSystemNodesFilter;
import org.sevensource.magnolia.backup.support.SimpleBackupUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupExecutor {
	private static final Logger logger = LoggerFactory.getLogger(BackupExecutor.class);

	public static final String VALID_FILENAME_PATTERN = "[\\\\/:*?\"<>|\\s]";

	private static final List<String> SPLITTABLE_NODE_TYPES =
			Arrays.asList(NodeTypes.Folder.NAME, NodeTypes.Content.NAME, NodeTypes.Page.NAME);



	private static final DateTimeFormatter dtFormatter = new DateTimeFormatterBuilder()
			.appendPattern("yyyy")
			.appendLiteral("-")
			.appendPattern("MM")
			.appendLiteral("-")
			.appendPattern("dd")
			.appendLiteral("T")
			.appendPattern("HHmmss")
			.toFormatter();

	private final List<SimpleBackupWorkspaceConfiguration> configurations;
	private final Path exportBasePath;
	private final SystemContext ctx;
	private final SimpleBackupJobFileDescriptor backupDescriptor = new SimpleBackupJobFileDescriptor();

	public BackupExecutor(List<SimpleBackupWorkspaceConfiguration> definitions, Path basePath, SystemContext ctx) {
		this.configurations = definitions;
		this.exportBasePath = basePath;
		this.ctx = ctx;
	}

	public void run() {
		validateBackupPath(exportBasePath);

		final List<BackupJobDefinition> jobDefinitions = configurations
			.stream()
			.map(wsDef -> {
				Path wsBackupPath = exportBasePath.resolve(wsDef.getWorkspace().toLowerCase());
				return new BackupJobDefinition(
						wsBackupPath,
						wsDef.getWorkspace(),
						wsDef.getPath(),
						wsDef.isSplit(),
						wsDef.isCompress() ? Compression.ZIP : Compression.NONE);
			})
			.collect(Collectors.toList());

		final StopWatch totalStopWatch = StopWatch.createStarted();
		log("Starting Backup into " + exportBasePath.toString());

		for(BackupJobDefinition jobDef : jobDefinitions) {
			final StopWatch jobStopWatch = StopWatch.createStarted();

			final String workspace = jobDef.getWorkspace();

			log("Starting backup of workspace " + workspace);

			final Path workspaceBackupPath = createWorkspaceBackupDirectory(workspace);
			final List<String> nodesToBackup = getExportNodes(jobDef);


			for(String backupNode : nodesToBackup) {
				final StopWatch nodeJobStopWatch = StopWatch.createStarted();
				log("Starting backup of node " + backupNode +" in workspace " + workspace);

				final String backupFilename = createBackupFilename(workspace, backupNode);
				final String backupFilesystemFilename = createBackupFilesystemFilename(backupFilename, jobDef.getCompression());
				final Path backupFilepath = workspaceBackupPath.resolve(backupFilesystemFilename);

				final boolean isBackupJobRootPath = backupNode.equals(jobDef.getRepositoryRootPath());

				final List<String> filteredNodes;
				if(isBackupJobRootPath) {
					filteredNodes = nodesToBackup.stream()
							.filter(n -> !n.equals(backupNode))
							.collect(Collectors.toList());
				} else {
					filteredNodes = Collections.emptyList();
				}

				doBackup(workspace, backupNode, filteredNodes, backupFilename, backupFilepath, jobDef.getCompression());

				final Path relativeBackupFilepath = exportBasePath.relativize(backupFilepath);
				final String backupDescriptorNodePath = backupNode.replaceFirst("/[^/]+$", "/");

				backupDescriptor.addWorkspaceItem(workspace, backupDescriptorNodePath, relativeBackupFilepath);
				log("Finished backup of node " + backupNode + " in workspace " + jobDef.getWorkspace() + " in " + getTimeAsString(nodeJobStopWatch));
			}

			backupDescriptor.addWorkspace(workspace);
			log("Finished backup of workspace " + jobDef.getWorkspace() + " in " + getTimeAsString(jobStopWatch));
		}

		log("Writing backup jobfile to " + exportBasePath);
		backupDescriptor.serialize(exportBasePath);

		log("Finished Backup in " + getTimeAsString(totalStopWatch));
	}

	private List<String> getExportNodes(BackupJobDefinition job) {

		final List<String> nodesToBackup = new ArrayList<>();
		nodesToBackup.add(job.getRepositoryRootPath());

		try {
			final Session session = ctx.getJCRSession(job.getWorkspace());
			final Node node = session.getNode(job.getRepositoryRootPath());
			final Iterable<Node> childNodes = JcrUtils.getChildNodes(node);
			for (Node childNode : childNodes) {
				final String primaryNodeType = childNode.getPrimaryNodeType().getName();
				if (SPLITTABLE_NODE_TYPES.contains(primaryNodeType)) {
					nodesToBackup.add(childNode.getPath());
				}
			}
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		} finally {
			ctx.release();
		}
		return nodesToBackup;
	}

	private void doBackup(String workspace, String nodePath, List<String> filteredNodes, String backupFilename, Path destination, JcrExportCommand.Compression compression) {

		if(logger.isDebugEnabled()) {
			logger.debug("Backing up node {} in workspace {} to {}", nodePath, workspace, destination);
		}

		try (
				final OutputStream out = new FileOutputStream(destination.toFile());
				final OutputStream decoratedOs = decorateOutputStream(out, backupFilename, compression)
		) {
			final Session session = ctx.getJCRSession(workspace);
			final ContentHandler contentHandler = getContentHandler(decoratedOs);

			final ContentDecorator contentDecorator = new ExcludeNodePathsAndSystemNodesFilter(filteredNodes);

			final Node node = contentDecorator.wrapNode( session.getNode(nodePath) );

			final SystemViewExporter exporter = new SystemViewExporter(session, contentHandler, true, true);
			exporter.export(node);
		} catch (PathNotFoundException ex) {
			throw new IllegalArgumentException("Path " + nodePath + " was not found for export", ex);
		} catch (RepositoryException ex) {
			throw new IllegalStateException("A repository exception occurred", ex);
		} catch (FileNotFoundException ex) {
			throw new IllegalArgumentException("Cannot open file '" + destination.toString() + "' for writing during backup. ", ex);
		} catch (Exception ex) {
			throw new RuntimeException("An exception occurred during export", ex);
		} finally {
			ctx.release();
		}
	}

	private ContentHandler getContentHandler(OutputStream out) {
		final NamespaceFilter filter = new NamespaceFilter("sv", "xsi");
		filter.setContentHandler(XmlContentHandlerFactory.newXmlContentHandler(out, false));
		return filter;
	}

	private OutputStream decorateOutputStream(OutputStream os, String filename, JcrExportCommand.Compression compression) throws IOException {
		switch (compression) {
			case ZIP:
				final ZipOutputStream zipOutputStream = new ZipOutputStream(os);
				zipOutputStream.putNextEntry(new ZipEntry(filename));
				return zipOutputStream;
			case GZ:
				return new GZIPOutputStream(os);
			default:
				return os;
		}
	}

	protected String createBackupFilename(String workspace, String nodePath) {
		final StringBuilder filename = new StringBuilder();
		filename.append( sanitizeFilename( workspace.toLowerCase() ) );

		if(! "/".equals(nodePath)) {
			String cleanedNodePath = nodePath
					.replaceAll("/{2,}", "/")
					.replace("/", ".");

			try {
				cleanedNodePath = URLEncoder.encode(cleanedNodePath, StandardCharsets.UTF_8.name());
			} catch(UnsupportedEncodingException e) {
				throw new IllegalStateException("Platform does not support encoding", e);
			}

			filename.append( sanitizeFilename( cleanedNodePath.toLowerCase() ) );
		}

		filename.append(".xml");
		return filename.toString();
	}

	protected String createBackupFilesystemFilename(String filename, JcrExportCommand.Compression compression) {
		final String compressionExtension;
		if(compression == Compression.NONE) {
			compressionExtension = "";
		} else {
			compressionExtension = "." + compression.name().toLowerCase();
		}

		return filename + compressionExtension;
	}

	private Path createWorkspaceBackupDirectory(String workspace) {
		final Path workspacePath = exportBasePath.resolve( sanitizeFilename(workspace) );
		return SimpleBackupUtils.createDirectory(workspacePath);
	}

	private static void validateBackupPath(Path basePath) {
		if(!basePath.toFile().exists() ||
				!basePath.toFile().isDirectory() ||
				!Files.isWritable(basePath)) {
			logger.error("Cannot backup repository into invalid basePath '{}'", basePath);
			throw new IllegalArgumentException("Cannot backup repository into nonexistant or non-writable basePath " + basePath);
		}
	}

	private String sanitizeFilename(String in) {
		return in.replaceAll(VALID_FILENAME_PATTERN, "").toLowerCase();
	}

	private void log(String logMessage) {
		logger.info(logMessage);

		final String msg = String.format(
				"%s: %s", dtFormatter.format(LocalDateTime.now()), logMessage
				);

		backupDescriptor.log(msg, this.exportBasePath);
	}

	private String getTimeAsString(StopWatch w) {
		return w.getTime(TimeUnit.SECONDS) + " seconds";
	}
}

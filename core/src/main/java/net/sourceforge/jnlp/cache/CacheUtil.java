// Copyright (C) 2001-2003 Jon A. Maxwell (JAM)
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

package net.sourceforge.jnlp.cache;

import net.adoptopenjdk.icedteaweb.Assert;
import net.adoptopenjdk.icedteaweb.StringUtils;
import net.adoptopenjdk.icedteaweb.client.controlpanel.CacheFileInfo;
import net.adoptopenjdk.icedteaweb.client.controlpanel.CacheIdInfo;
import net.adoptopenjdk.icedteaweb.client.parts.downloadindicator.DownloadIndicator;
import net.adoptopenjdk.icedteaweb.client.parts.downloadindicator.DummyDownloadIndicator;
import net.adoptopenjdk.icedteaweb.io.FileUtils;
import net.adoptopenjdk.icedteaweb.jnlp.element.EntryPoint;
import net.adoptopenjdk.icedteaweb.jnlp.element.application.AppletDesc;
import net.adoptopenjdk.icedteaweb.jnlp.element.application.ApplicationDesc;
import net.adoptopenjdk.icedteaweb.jnlp.element.extension.InstallerDesc;
import net.adoptopenjdk.icedteaweb.jnlp.version.VersionString;
import net.adoptopenjdk.icedteaweb.logging.Logger;
import net.adoptopenjdk.icedteaweb.logging.LoggerFactory;
import net.adoptopenjdk.icedteaweb.resources.ResourceTracker;
import net.adoptopenjdk.icedteaweb.resources.cache.Cache;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.runtime.classloader.JNLPClassLoader;

import javax.jnlp.DownloadServiceListener;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.sourceforge.jnlp.util.UrlUtils.FILE_PROTOCOL;
import static net.sourceforge.jnlp.util.UrlUtils.JAR_PROTOCOL;

/**
 * Provides static methods to interact with the cache, download
 * indicator, and other utility methods.
 *
 * @author <a href="mailto:jmaxwell@users.sourceforge.net">Jon A. Maxwell (JAM)</a> - initial author
 * @version $Revision: 1.17 $
 */
public class CacheUtil {

    private static final Logger LOG = LoggerFactory.getLogger(CacheUtil.class);

    private static final List<String> NON_CACHEABLE_PROTOCOLS = Arrays.asList(FILE_PROTOCOL, JAR_PROTOCOL);

    /**
     * Caches a resource and returns a URL for it in the cache;
     * blocks until resource is cached. If the resource location is
     * not cacheable (points to a local file, etc) then the original
     * URL is returned.
     *
     * @param location location of the resource
     * @param version  the version, or {@code null}
     * @return either the location in the cache or the original location
     */
    public static File downloadAndGetCacheFile(final URL location, final VersionString version) {
        try {
            final ResourceTracker rt = new ResourceTracker();
            rt.addResource(location, version);
            return rt.getCacheFile(location);
        } catch (Exception ex) {
            if (location.toString().startsWith("file:")) {
                try {
                    return new File(location.toURI());
                } catch (URISyntaxException ignored) {
                }
            }
            return null;
        }
    }

    public static void logCacheIds(String filter) {
        List<CacheIdInfo> items = Cache.getCacheIds(filter);
        if (JNLPRuntime.isDebug()) {
            for (CacheIdInfo id : items) {
                LOG.info("{} ({}) [{}]", id.getId(), id.getType(), id.getFileInfos().size());
                for (CacheFileInfo cacheFileInfo : id.getFileInfos()) {
                    LOG.info("  * {}", cacheFileInfo);
                }
            }
        } else {
            for (CacheIdInfo id : items) {
                LOG.info(id.getId());
            }
        }
    }

    /**
     * Returns whether the resource can be cached as a local file;
     * if not, then URLConnection.openStream can be used to obtain
     * the contents.
     *
     * @param source the url of resource
     * @return whether this resource can be cached
     */
    public static boolean isNonCacheable(URL source) {
        Assert.requireNonNull(source, "source");
        return NON_CACHEABLE_PROTOCOLS.contains(source.getProtocol());
    }

    /**
     * Returns whether the resource can be cached as a local file;
     * if not, then URLConnection.openStream can be used to obtain
     * the contents.
     *
     * @param source the url of resource
     * @return whether this resource can be cached
     */
    public static boolean isCacheable(URL source) {
        return !isNonCacheable(source);
    }


    /**
     * Converts a URL into a local path string within the given directory. For
     * example a url with subdirectory /tmp/ will
     * result in a File that is located somewhere within /tmp/
     *
     * @param location the url
     * @param root     the subdirectory
     * @return the file
     */
    public static File urlToPath(URL location, String root) {
        if (root == null) {
            throw new NullPointerException();
        }

        StringBuilder path = new StringBuilder();

        path.append(location.getProtocol());
        path.append(File.separatorChar);
        path.append(location.getHost());
        path.append(File.separatorChar);

        if (location.getPort() > 0) {
            path.append(location.getPort());
        } else {
            path.append(location.getDefaultPort());
        }
        path.append(File.separatorChar);

        final String locationPath = location.getPath();
        final String queryPart = location.getQuery();
        if (locationPath.contains("..") || (queryPart != null && queryPart.contains(".."))) {
            try {
                /**
                 * if path contains .. then it can harm local system
                 * So without mercy, hash it
                 */
                String hexed = hex(new File(locationPath).getName(), locationPath);
                return new File(root, path.append(File.separatorChar).append(hexed).toString());
            } catch (NoSuchAlgorithmException ex) {
                // should not occur, cite from javadoc:
                // every java implementation should support
                // MD5 SHA-1 SHA-256
                throw new RuntimeException(ex);
            }
        } else {
            path.append(locationPath.replace('/', File.separatorChar));
            if (!StringUtils.isBlank(queryPart)) {
                path.append(".").append(queryPart);
            }

            File candidate = new File(root, FileUtils.sanitizePath(path.toString()));
            try {
                if (candidate.getName().length() > 255) {
                    /**
                     * When filename is longer then 255 chars, then then various
                     * filesystems have issues to save it. By saving the file by its
                     * sum, we are trying to prevent collision of two files differs in
                     * suffixes (general suffix of name, not only 'filetype suffix')
                     * only. It is also preventing bug when truncate (files with 1000
                     * chars hash in query) cuts to much.
                     */
                    String hexed = hex(candidate.getName(), candidate.getName());
                    candidate = new File(candidate.getParentFile(), hexed);
                }
            } catch (NoSuchAlgorithmException ex) {
                // should not occur, cite from javadoc:
                // every java implementation should support
                // MD5 SHA-1 SHA-256
                throw new RuntimeException(ex);
            }
            return candidate;
        }
    }

    public static String hex(String origName, String candidate) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] sum = md.digest(candidate.getBytes(UTF_8));
        //convert the byte to hex format method 2
        StringBuilder hexString = new StringBuilder();
        for (byte b : sum) {
            hexString.append(Integer.toHexString(0xFF & b));
        }
        String extension = "";
        int i = origName.lastIndexOf('.');
        if (i > 0) {
            extension = origName.substring(i);//contains dot
        }
        if (extension.length() < 10 && extension.length() > 1) {
            hexString.append(extension);
        }
        return hexString.toString();
    }

    /**
     * Waits until the resources are downloaded, while showing a
     * progress indicator.
     *
     * @param jnlpClassLoader the classloader
     * @param tracker         the resource tracker
     * @param resources       the resources to wait for
     * @param title           name of the download
     */
    public static void waitForResources(final JNLPClassLoader jnlpClassLoader, final ResourceTracker tracker, final URL[] resources, final String title) {
        try {
            final DownloadIndicator indicator = Optional.ofNullable(JNLPRuntime.getDefaultDownloadIndicator())
                    .orElseGet(() -> new DummyDownloadIndicator());
            final DownloadServiceListener listener = getDownloadServiceListener(jnlpClassLoader, title, resources, indicator);
            try {
                for (URL url : resources) {
                    tracker.addDownloadListener(url, resources, listener);
                }
                tracker.waitForResources(resources);
            } finally {
                indicator.disposeListener(listener);
            }
        } catch (Exception ex) {
            LOG.error("Downloading of resources ended with error", ex);
        }
    }

    private static DownloadServiceListener getDownloadServiceListener(final JNLPClassLoader jnlpClassLoader, final String title, final URL[] undownloaded, final DownloadIndicator indicator) {
        final String progressClass = getProgressClass(jnlpClassLoader);

        if (progressClass != null) {
            try {
                final Class<?> downloadProgressIndicatorClass = jnlpClassLoader.loadClass(progressClass);
                return (DownloadServiceListener) downloadProgressIndicatorClass.newInstance();
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
                LOG.warn(format("Could not load progress class '%s' specified in JNLP file, " +
                        "use default download progress indicator instead.", progressClass), ex);
            }
        }

        return indicator.getListener(title, undownloaded);
    }

    private static String getProgressClass(JNLPClassLoader jnlpClassLoader) {
        final EntryPoint entryPoint = jnlpClassLoader.getJNLPFile().getEntryPointDesc();

        if (entryPoint instanceof ApplicationDesc) {
            return ((ApplicationDesc) entryPoint).getProgressClass();
        } else if (entryPoint instanceof AppletDesc) {
            return ((AppletDesc) entryPoint).getProgressClass();
        } else if (entryPoint instanceof InstallerDesc) {
            return ((InstallerDesc) entryPoint).getProgressClass();
        }
        return null;
    }
}

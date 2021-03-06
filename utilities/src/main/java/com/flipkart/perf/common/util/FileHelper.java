package com.flipkart.perf.common.util;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Helper to do various file operations
 */
public class FileHelper {
    public static String persistStream(InputStream libStream, String targetFile) throws IOException {
        return persistStream(libStream, targetFile, false);
    }

    public static String persistStream(InputStream libStream, String targetFile, boolean append) throws IOException {
        byte[] buffer = new byte[8024];
        createFile(targetFile);
        BufferedInputStream bis = new BufferedInputStream(libStream);

        FileOutputStream fos = new FileOutputStream(targetFile, append);
        FileLock fLock = acquireLock(fos, 30000);
        BufferedOutputStream bos = new BufferedOutputStream(fos);

        int bytesRead;
        while((bytesRead = bis.read(buffer)) > 0) {
            bos.write(buffer,0,bytesRead);
        }
        bos.flush();
        bis.close();
        fLock.release();
        bos.close();
        return targetFile;
    }

    private static FileLock acquireLock(FileOutputStream fos, int timeoutMS) throws IOException {
        FileLock fLock = null;
        long timePassed = 0;
        while(timePassed < timeoutMS && fLock == null) {
            fLock = fos.getChannel().tryLock();
            try {
                Clock.sleep(10);
                timePassed += 10;
            } catch (InterruptedException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
        if(fLock == null)
            throw new RuntimeException("Couldn't acquire Lock");
        return fLock;
    }

    private static FileLock acquireLock(FileInputStream fis, int timeoutMS) throws IOException {
        FileLock fLock = null;
        long timePassed = 0;
        while(timePassed < timeoutMS && fLock == null) {
            fLock = fis.getChannel().tryLock();
            try {
                Clock.sleep(10);
                timePassed += 10;
            } catch (InterruptedException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
        if(fLock == null)
            throw new RuntimeException("Couldn't acquire Lock");
        return fLock;
    }

    public static void createFile(String filePath) throws IOException {
        File file = new File(filePath);
        if(!file.exists())
            file.createNewFile();
    }

    public static void unzip(InputStream libInputStream, String path) throws IOException {
        createFolder(path);
        ZipInputStream zis = new ZipInputStream(libInputStream);

        try {
            ZipEntry ze;
            byte[] buffer = new byte[4096];

            while ((ze = zis.getNextEntry()) != null){
                if (ze.isDirectory())
                    continue;

                String fileName = ze.getName();

                int bytesRead;
                String filePath = path + File.separator + fileName;
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(filePath);
                    while ((bytesRead = zis.read(buffer)) > 0){
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                catch(IOException ioe) {
                    ioe.printStackTrace();
                } finally {
                    if(fos != null) {
                        fos.flush();
                        fos.close();
                    }
                    zis.closeEntry();
                }
            }
        }
        catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        finally {
            if (zis != null)
                zis.close();
        }
    }

    private static void createFolder(String path) {
        File file = new File(path);
        if(!file.exists())
            file.mkdirs();

    }

    public static void rename(String platformLibPath, String newPath) {
        new File(platformLibPath).renameTo(new File(newPath));
    }

    public static void remove(String path) {
        File pathHandle = new File(path);
        if(pathHandle.isDirectory()) {
            File[] files = pathHandle.listFiles();
            for(File file : files) {
                if(file.isDirectory())
                    remove(file.getAbsolutePath());
                file.delete();
            }
            pathHandle.delete();
        }
        else
            pathHandle.delete();
    }

    public static List<File> pathFiles(String path, boolean recursively) {
        List<File> allFiles = new ArrayList<File>();
        File jobPathObj = new File(path);
        File[] pathFiles = jobPathObj.listFiles();
        if(pathFiles != null) {
            for(File pathFile : pathFiles) {
                if(pathFile.isDirectory()) {
                    if(recursively)
                        allFiles.addAll(pathFiles(pathFile.getAbsolutePath(), recursively));
                }
                else
                    allFiles.add(pathFile);
            }
        }
        return allFiles;
    }

    // Change it to use Byte data instead of line. Would work for binary data as well
    public static String readContent(InputStream stream) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(stream));
        StringBuffer content = new StringBuffer();
        String line = null;
        try {
            while((line = br.readLine()) != null)
                content.append(line + "\n");
        }
        catch (java.lang.IllegalStateException stateException) {
            // Eating away the exception
        }
        catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            throw e;
        }
        finally {
            try {
                br.close();
            }
            catch (java.lang.IllegalStateException stateException) {
                // Eating away the exception
            }
        }
        return content.toString();
    }

    public static void move(String src, String target) {
        File srcFile = new File(src);
        if(srcFile.exists())
            srcFile.renameTo(new File(target));
    }

    /**
     * Assumption is the input path contains the file name aswell.
     * @param statFilePath
     */
    public static void createFilePath(String statFilePath) {
        String parentPath = new File(statFilePath).getParent();
        createFolder(parentPath);
    }

    public static void close(OutputStream os) throws IOException {
        if(os != null)
            os.close();
    }

    public static void close(Writer w) throws IOException {
        if(w != null)
            w.close();
    }

    public static void close(RandomAccessFile raf) throws IOException {
        if(raf != null)
            raf.close();
    }

    public static BufferedWriter bufferedWriter(String file, boolean append) throws FileNotFoundException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, append)));
    }

    public static RandomAccessFile randomAccessFile(File file, String r) throws FileNotFoundException {
        return new RandomAccessFile(file, "r");
    }

    public static BufferedReader bufferedReader(String absolutePath) throws FileNotFoundException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(absolutePath)));
    }

    public static void close(BufferedReader br) throws IOException {
        br.close();
    }

    public static void deleteRecursively(File file){
        if(!file.exists()) throw new RuntimeException("Can't delete, " + file.getAbsolutePath() + " doesn't exists!");
        delete(file);
    }

    private static void delete(File file){
        if(file.isDirectory()){
            if(file.list().length==0){
                file.delete();
            }else{
                String files[] = file.list();
                for (String temp : files) {
                    File fileDelete = new File(file, temp);
                    delete(fileDelete);
                }
                if(file.list().length==0){
                    file.delete();
                }
            }
        }else{
            file.delete();
        }
    }
}

package com.flipkart.perf.server.resource;

import com.codahale.metrics.annotation.Timed;
import com.flipkart.perf.common.util.ClassHelper;
import com.flipkart.perf.common.util.FileHelper;
import com.flipkart.perf.function.FunctionParameter;
import com.flipkart.perf.inmemorydata.SharedDataInfo;
import com.flipkart.perf.server.cache.LibCache;
import com.flipkart.perf.server.config.ResourceStorageFSConfig;
import com.flipkart.perf.server.domain.FunctionInfo;
import com.flipkart.perf.server.util.ObjectMapperUtil;
import com.flipkart.perf.server.util.ResponseBuilder;
import com.google.common.collect.Multimap;
import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataParam;
import org.codehaus.jackson.map.ObjectMapper;
import org.reflections.Reflections;
import org.reflections.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;


/**
 * Resource that deploys libs, file resources on the server
 */
@Path("/loader-server/resourceTypes")
public class DeployResourcesResource {
    private ResourceStorageFSConfig resourceStorageFSConfig;
    private LibCache libCache;
    private static ObjectMapper objectMapper = ObjectMapperUtil.instance();
    private static Logger logger = LoggerFactory.getLogger(DeployResourcesResource.class);

    public DeployResourcesResource(ResourceStorageFSConfig resourceStorageFSConfig) throws MalformedURLException {
        this.resourceStorageFSConfig = resourceStorageFSConfig;
        this.libCache = LibCache.instance();
        deployUnDeployedUDFLibs();
    }

    // UDF Libs which are copied as part of deployment will always be un deployed before 1st loader-server start
    private void deployUnDeployedUDFLibs() {
        for(File unDeployedUDFLib : new File(resourceStorageFSConfig.getUdfUnDeployedLibsPath()).listFiles()) {
            try {
                deployUDF(new FileInputStream(unDeployedUDFLib), unDeployedUDFLib.getName());
                unDeployedUDFLib.delete();
            } catch (Exception e) {
                logger.error(e.getLocalizedMessage());
                logger.error("Exception in deploying undeployed UDFs", e);
            }
        }
    }

    static class CustomClassLoader extends URLClassLoader {
        public CustomClassLoader(URL[] urls) {
            super(urls);
        }

        @Override
        public void addURL(URL url) {
            super.addURL(url);
        }
    }

    /**
     Following call simulates html form post call, where somebody uploads a file to server
     curl
     -X POST
     -H "Content-Type: multipart/form-data"
     -F "lib=@Path-To-Jar-File"
     http://localhost:8888/loader-server/resourceTypes/udfLibs
     *
     *
     *
     * @param libInputStream jar input stream
     * @param libFileDetails Lib file meta details
     * @throws java.io.IOException
     */
    @Path("/udfLibs")
    @POST
    @Timed
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    synchronized public Map<String, FunctionInfo> deployUDF(
            @FormDataParam("lib") InputStream libInputStream,
            @FormDataParam("lib") FormDataContentDisposition libFileDetails) throws IOException, ClassNotFoundException, NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {

        return deployUDF(libInputStream, libFileDetails.getFileName());
    }

    private Map<String, FunctionInfo> deployUDF(InputStream libInputStream, String udfFileName) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        String userLibPath = resourceStorageFSConfig.getUdfLibsPath()
                + File.separator
                + udfFileName;

        FileHelper.persistStream(libInputStream, userLibPath);

        Map<String, FunctionInfo> discoveredUserFunctions = discoverUserFunctions(userLibPath);

        persistDiscoveredUserFunctions(udfFileName, discoveredUserFunctions);

        this.libCache.refreshClassLibMap();

        return discoveredUserFunctions;
    }

    /**     * Persist user class and jar mapping
     * Persist Class information which could be later presented vie http get end point or UI
     * @param libFileName
     * @param discoveredUserFunctions
     * @throws java.io.IOException
     */
    private void persistDiscoveredUserFunctions(String libFileName, Map<String, FunctionInfo> discoveredUserFunctions) throws IOException {
        for(String userFunction : discoveredUserFunctions.keySet()) {
            mergeMappingFile(resourceStorageFSConfig.getUdfLibsPath()
                    + File.separator
                    + libFileName,
                    userFunction);

            FunctionInfo functionInfo = discoveredUserFunctions.get(userFunction);
            String functionInfoFile = resourceStorageFSConfig.getUserClassInfoPath() + File.separator + userFunction + ".info.json";
            FileHelper.createFile(functionInfoFile);
            objectMapper.writeValue(new File(functionInfoFile), functionInfo);
        }
    }

    enum MapKey {
        LIB,CLASS;
    }

    /**
     *
     * @param mapKey takes LIB or CLASS as value. Default value is LIB
     * @return returns either Map(lib -> list of class) or Map(class -> Lib) depending upon mapKey
     * @throws java.io.IOException
     */
    @Path("/udfLibs")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map getLibs(@QueryParam("mapKey") @DefaultValue("LIB") String mapKey) throws IOException {

        switch(MapKey.valueOf(mapKey)) {
            case LIB:
                return libCache.getLibsMapWithLibAsKey();
            case CLASS:
                return libCache.getLibsMapWithClassAsKey();
            default:
                throw new WebApplicationException(400);
        }
    }


    /**
     Following call simulates html form post call, where somebody uploads a file to server
     curl
     -X POST
     -H "Content-Type: multipart/form-data"
     -F "lib=@Path-To-Zip-File-Containing-Platform-Lib-File"
     http://localhost:8888/loader-server/resourceTypes/platformLibs

     * @param libInputStream zip containing platform jars
     */
    @Path("/platformLibs")
    @POST
    @Timed
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    synchronized public void deployPlatformLib(
            @FormDataParam("lib") InputStream libInputStream){
        String platformZipPath = resourceStorageFSConfig.getPlatformLibPath()+ File.separator + "platform.zip";
        String tmpPlatformZipPath = resourceStorageFSConfig.getPlatformLibPath()+ File.separator + "platform.zip.tmp";

        try {
            FileHelper.move(platformZipPath, tmpPlatformZipPath);
            FileHelper.persistStream(libInputStream, platformZipPath);
            FileHelper.unzip(new FileInputStream(platformZipPath), resourceStorageFSConfig.getPlatformLibPath());
            FileHelper.remove(tmpPlatformZipPath);

        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            FileHelper.move(tmpPlatformZipPath, platformZipPath);
        }
        finally {
            this.libCache.refreshPlatformLibPath();
        }
    }

    @Path("/platformLibs")
    @GET
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    synchronized public List getPlatformLib(){
        return Arrays.asList(new File(resourceStorageFSConfig.getPlatformLibPath()).list());
    }

    /**
     Following call simulates html form post call, where somebody uploads a file to server
     curl
     -X POST
     -H "Content-Type: multipart/form-data"
     -F "lib=@Path-To-Zip-File-Containing-Platform-Lib-File"
     http://localhost:8888/loader-server/resourceTypes/inputFiles

     * @param inputStream zip containing platform jars
     */
    @Path("/inputFiles")
    @POST
    @Timed
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    synchronized public Response deployInputFiles(
            @FormDataParam("file") InputStream inputStream,
            @FormDataParam("resourceName") String resourceName) {
        File resourceFile = new File(resourceStorageFSConfig.getInputFilePath(resourceName));
        if(resourceFile.exists()) {
            throw new WebApplicationException(ResponseBuilder.resourceAlreadyExists("inputFile", resourceName));
        }

        if(resourceName == null || resourceName.trim().equals(""))
            throw new WebApplicationException(ResponseBuilder.badRequest("resourceName can not be empty"));

        FileHelper.createFilePath(resourceFile.getAbsolutePath());
        try {
            FileHelper.persistStream(inputStream, resourceFile.getAbsolutePath());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(resourceStorageFSConfig.getInputFileAgentDeploymentPath(resourceName)), new HashMap());
            return ResponseBuilder.resourceCreated("inputFile", resourceName);
        } catch (IOException e) {
            e.printStackTrace();
            FileHelper.remove(resourceStorageFSConfig.getInputFileFolderPath(resourceName));
            throw new WebApplicationException(ResponseBuilder.internalServerError(e));
        }
    }

    @Path("/inputFiles")
    @GET
    @Timed
    @Produces(MediaType.APPLICATION_JSON)
    synchronized public List<String> getInputFiles() throws IOException {
        File inputFilesPath = new File(resourceStorageFSConfig.getInputFilesPath());
        if(inputFilesPath.exists()) {
            return Arrays.asList(inputFilesPath.list());
        }
        return new ArrayList<String>();
    }

    @Path("/inputFiles/{resourceName}")
    @GET
    @Timed
    @Produces(MediaType.TEXT_PLAIN)
    public InputStream getInputFile(@PathParam("resourceName") String resourceName) throws IOException {
        File resourceFile = new File(resourceStorageFSConfig.getInputFilePath(resourceName));
        if(!resourceFile.exists()) {
            throw new WebApplicationException(ResponseBuilder.resourceNotFound("inputFile", resourceName));
        }
        return new FileInputStream(resourceFile);
    }

    @Path("/inputFiles/{resourceName}")
    @PUT
    @Timed
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    synchronized public void updateInputFile(
            @FormDataParam("file") InputStream inputStream, @PathParam("resourceName") String resourceName) throws IOException {
        File resourceFile = new File(resourceStorageFSConfig.getInputFilePath(resourceName));
        if(!resourceFile.exists()) {
            throw new WebApplicationException(ResponseBuilder.resourceNotFound("inputFile", resourceName));
        }
        resourceFile.delete();
        FileHelper.persistStream(inputStream, resourceFile.getAbsolutePath());
    }

    @Path("/inputFiles/{resourceName}")
    @DELETE
    @Timed
    @Produces(MediaType.TEXT_PLAIN)
    public void deleteInputFile(@PathParam("resourceName") String resourceName) throws IOException {
        File inputFileFolderPath = new File(resourceStorageFSConfig.getInputFileFolderPath(resourceName));
        if(!inputFileFolderPath.exists()) {
            throw new WebApplicationException(ResponseBuilder.resourceNotFound("inputFile", resourceName));
        }
        FileHelper.remove(inputFileFolderPath.getAbsolutePath());
    }

    /**
     * Discover Performance Functions from the uploader userLibJar
     * @param userLibJar
     * @return
     * @throws java.io.IOException
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException
     * @throws java.lang.reflect.InvocationTargetException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    public Map<String,FunctionInfo> discoverUserFunctions(String userLibJar) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        Map<String, FunctionInfo> discoveredUserFunctions = new HashMap<String, FunctionInfo>();

        URLClassLoader loader = (URLClassLoader)ClassLoader.getSystemClassLoader();
        CustomClassLoader customClassLoader = new CustomClassLoader(loader.getURLs());
        File platformLibPath = new File(this.resourceStorageFSConfig.getPlatformLibPath());
        if(platformLibPath.exists()) {
            File[] platformLibs = platformLibPath.listFiles();
            for(File platformLib : platformLibs) {
                customClassLoader.addURL(new URL("file://" + platformLib.getAbsolutePath()));
            }
        }

        customClassLoader.addURL(new URL("file://" + userLibJar));
        System.out.println("User Lib Path = " + userLibJar);
        Reflections reflections = new Reflections("");
        reflections.scan(new URL("file://"+userLibJar));

        Store reflectionStore = reflections.getStore();
        Map<String, Multimap<String, String>> storeMap = reflectionStore.getStoreMap();
        Multimap<String,String> subTypesScanner = storeMap.get("SubTypesScanner");

        if(subTypesScanner != null) {
            Collection<String> performanceFunctions = subTypesScanner.get("com.flipkart.perf.function.PerformanceFunction");
            for(String performanceFunction : performanceFunctions) {
                if(!discoveredUserFunctions.containsKey(performanceFunction)) {
                    FunctionInfo functionInfo = new FunctionInfo().
                            setFunction(performanceFunction);
                    // Discover Usage description for the UDF
                    Object object = ClassHelper.getClassInstance(performanceFunction, new Class[]{}, new Object[]{}, customClassLoader);
                    Method method = ClassHelper.getMethod(performanceFunction , "description", new Class[]{}, customClassLoader);
                    functionInfo.setDescription((List<String>) method.invoke(object, new Object[]{}));

                    // Discover Input parameters for the UDF
                    method = ClassHelper.getMethod(performanceFunction , "inputParameters", new Class[]{}, customClassLoader);
                    functionInfo.setInputParameters((LinkedHashMap<String, FunctionParameter>) method.invoke(object, new Object[]{}));

                    // Discover Output parameters for the UDF
                    method = ClassHelper.getMethod(performanceFunction , "outputParameters", new Class[]{}, customClassLoader);
                    functionInfo.setOutputParameters((LinkedHashMap<String, FunctionParameter>) method.invoke(object, new Object[]{}));

                    // Discover Custom timers for the UDF
                    method = ClassHelper.getMethod(performanceFunction , "customTimers", new Class[]{}, customClassLoader);
                    functionInfo.setCustomTimers((List<String>) method.invoke(object, new Object[]{}));

                    // Discover Custom Counters for the UDF
                    method = ClassHelper.getMethod(performanceFunction , "customCounters", new Class[]{}, customClassLoader);
                    functionInfo.setCustomCounters((List<String>) method.invoke(object, new Object[]{}));

                    // Discover Custom Histograms for the UDF
                    method = ClassHelper.getMethod(performanceFunction , "customHistograms", new Class[]{}, customClassLoader);
                    functionInfo.setCustomHistograms((List<String>) method.invoke(object, new Object[]{}));

                    // Discover Input parameters for the UDF
                    method = ClassHelper.getMethod(performanceFunction , "sharedData", new Class[]{}, customClassLoader);
                    functionInfo.setSharedData((LinkedHashMap<String, SharedDataInfo>) method.invoke(object, new Object[]{}));

                    discoveredUserFunctions.put(performanceFunction, functionInfo);
                }
            }
        }
        return discoveredUserFunctions;
    }

    /**
     * Update Mapping file which has map of User Function Class and Jar containing that class
     * @param libPath
     * @param userFunctionClass
     * @throws java.io.IOException
     */
    synchronized private void mergeMappingFile(String libPath, String userFunctionClass) throws IOException {
        String mappingFile = resourceStorageFSConfig.getUserClassLibMappingFile();

        Properties prop = new Properties();
        FileHelper.createFile(mappingFile);
        InputStream mappingFileIS = new FileInputStream(mappingFile);
        try {
            FileHelper.createFile(mappingFile);
            prop.load(mappingFileIS);
            prop.put(userFunctionClass, libPath);
            prop.store(new FileOutputStream(mappingFile), "Class and Library Mapping");
        }
        catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        finally {
            mappingFileIS.close();
        }
    }
}
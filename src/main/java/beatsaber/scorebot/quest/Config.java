package beatsaber.scorebot.quest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Properties;

public class Config {
    private static Properties properties;

    static {
        properties = new Properties();
        String env = System.getenv("ENV");
        if (env == null) {
            env = "dev";
        }

        try {
            URL fileResource = Config.class.getResource("/" + env + ".properties");
            if (fileResource != null) {
                properties.load(fileResource.openStream());
            }
//          Check in current working dir for packaged jars
            File localFile = new File(env + ".properties");
            if (localFile.exists()) {
                properties.load(new FileInputStream(localFile));
            }
            properties.setProperty("env", env);
            // append to system properties if no other value set for that key
            for (String k : properties.stringPropertyNames()) {
                if (!System.getProperties().containsKey(k)) {
                    System.setProperty(k, properties.getProperty(k));
                }
            }
        } catch (IOException e) {
            System.err.println("The properties file for " + env + " could not be loaded");
        }
    }

    public static boolean hasProperty(String name) {
        String propValue = getProperty(name, null);
        return propValue != null;
    }

    public static String getProperty(String name) {
        String propValue = getProperty(name, null);
        return propValue;
    }

    public static int getInt(String prop) throws NumberFormatException {
        return Integer.parseInt(getProperty(prop));
    }

    public static long getLong(String prop, long def) throws NumberFormatException {
        return Long.parseLong(getProperty(prop, def + ""));
    }

    public static String getProperty(String name, String defaultValue) {
        String res = properties.getProperty(name);
        if (res != null) {
            return res;
        }
        return defaultValue;
    }
}

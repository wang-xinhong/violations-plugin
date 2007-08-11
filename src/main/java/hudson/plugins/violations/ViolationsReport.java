package hudson.plugins.violations;

import hudson.plugins.violations.parse.ParseXML;
import hudson.plugins.violations.parse.BuildModelParser;
import hudson.plugins.violations.render.FileModelProxy;
import hudson.plugins.violations.render.NoViolationsFile;
import hudson.plugins.violations.model.BuildModel;
import hudson.plugins.violations.util.RecurDynamic;
import hudson.plugins.violations.util.HelpHudson;

import java.lang.ref.WeakReference;
import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import hudson.model.HealthReport;
import hudson.model.Build;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * This contains the report for the violations
 * of a particular build.
 */
public class ViolationsReport {
    private static final Logger LOG
        = Logger.getLogger(ViolationsReport.class.getName());

    private Build build;
    private ViolationsConfig config;
    private Map<String, Integer> violations = new TreeMap<String, Integer>();
    private transient WeakReference<BuildModel> modelReference;

    /**
     * Set the build.
     * @param build the current build.
     */
    public void setBuild(Build build) {
        this.build = build;
    }

    /**
     * Get the build.
     * @return the build.
     */
    public Build getBuild() {
        return build;
    }

    /**
     * Set the config.
     * @param config the config.
     */
    public void setConfig(ViolationsConfig config) {
        this.config = config;
    }

    /**
     * Get the config.
     * @return the config.
     */
    public ViolationsConfig getConfig() {
        return config;
    }


    /**
     * Get the violation counts for the build.
     * @return a map of type to count.
     */
    public Map<String, Integer> getViolations() {
        return violations;
    }


    /**
     * Get the overall health for the build.
     * @return the health report, null if there are no counts.
     */
    public HealthReport getBuildHealth() {
        List<HealthReport> reports = getBuildHealths();
        HealthReport ret = null;
        for (HealthReport report: reports) {
            ret = HealthReport.min(ret, report);
        }
        return ret;
    }

    /**
     * Get a health report for each type.
     * @return a list of health reports.
     */
    public List<HealthReport> getBuildHealths() {
        List<HealthReport> ret = new ArrayList<HealthReport>();
        for (String type: config.getTypeConfigs().keySet()) {
            HealthReport health = getHealthReportFor(type);
            if (health != null) {
                ret.add(health);
            }
        }
        return ret;
    }

    /**
     * Get the health for a particulat type.
     * @param type the type to get the health for.
     * @return the health report.
     */
    public HealthReport getHealthReportFor(String type) {
        Integer count = violations.get(type);
        if (count == null || config.getTypeConfigs() == null) {
            return null;
        }
        int h =
            config.getTypeConfigs().get(type).getHealthFor(count);
        if (h < 0) {
            return new HealthReport(
                0, "No xml report files found for " + type);
        } else {
            return new HealthReport(
                h, "Number of " + type + " violations is " + count);
        }
    }

    /**
     * Get the detailed model for the build.
     * This is lazily build from an xml created during publisher action.
     * @return the build model.
     */
    public BuildModel getModel() {
        BuildModel model = null;
        if (modelReference != null) {
            model = modelReference.get();
            if (model != null) {
                return model;
            }
        }

        File xmlFile = new File(
            build.getRootDir(),
            MagicNames.VIOLATIONS + "/" + MagicNames.VIOLATIONS + ".xml");
        try {
            model = new BuildModel(xmlFile);
            ParseXML.parse(
                xmlFile, new BuildModelParser().buildModel(model));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to parse " + xmlFile, ex);
            return null;
        }

        modelReference = new WeakReference<BuildModel>(model);
        return model;
    }

    /**
     * Get the file model proxt for a file name.
     * @param name the name to use.
     * @return the file model proxy.
     */
    public FileModelProxy getFileModelProxy(
        String name) {
        BuildModel model = getModel();
        if (model == null) {
            return null;
        }
        return model.getFileModelMap().get(name);
    }

    /**
     * This gets called to display a particular violation file report.
     * @param token the current token in the path being parsed.
     * @param req the http/stapler request.
     * @param rsp the http/stapler response.
     * @return an object to handle the token.
     */
    public Object getDynamic(
        String token, StaplerRequest req, StaplerResponse rsp) {
        String name =  req.getRestOfPath();
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        FileModelProxy proxy = getFileModelProxy(name);
        if (proxy != null) {
            return new RecurDynamic(
                "", name, proxy.build(build).contextPath(req.getContextPath()));
        } else {
            return new RecurDynamic(
                "", name, new NoViolationsFile(name, build));
        }
    }

    /**
     * Get a map of type to type reports.
     * @return a map of type to type reports.
     */
    public Map<String, TypeReport> getTypeReports() {
        Map<String, TypeReport> ret =
            new TreeMap<String, TypeReport>();
        for (String t: violations.keySet()) {
            int c = violations.get(t);
            HealthReport health = getHealthReportFor(t);
            ret.put(
                t,
                new TypeReport(t, health.getIconUrl(), c));
        }
        return ret;
    }

    /**
     * Graph this report.
     * Note that for some reason, yet unknown, hudson seems
     * to pick an in memory ViolationsReport object and
     * not the report for the build.
     * Need to find the correct build from the URI.
     * @param req the request paramters
     * @param rsp the response.
     * @throws IOException if there is an error writing the graph.
     */
    public void doGraph(StaplerRequest req, StaplerResponse rsp)
        throws IOException {
        Build tBuild = build;
        int buildNumber = HelpHudson.findBuildNumber(req);
        if (buildNumber != 0) {
            tBuild = (Build)  build.getParent().getBuildByNumber(buildNumber);
            if (tBuild == null) {
                tBuild = build;
            }
        }
        ViolationsBuildAction r = tBuild.getAction(ViolationsBuildAction.class);
        if (r == null) {
            return;
        }
        r.doGraph(req, rsp);
    }

    /**
     * Report class for a particular type.
     */
    public class TypeReport {
        private final String type;
        private final String icon;
        private final int    number;

        /**
         * Create the report class for a type.
         * @param type the violation type.
         * @param icon the health icon to display.
         * @param number the number of violations.
         */
        public TypeReport(String type, String icon, int number) {
            this.type = type;
            this.icon = icon;
            this.number = number;
        }

        /**
         * Get the violation type.
         * @return the violation type.
         */
        public String getType() {
            return type;
        }

        /**
         * Get the health icon to display.
         * @return the health icon.
         */
        public String getIcon() {
            return icon;
        }

        /**
         * Get the number of violations.
         * @return the number.
         */
        public int getNumber() {
            return number;
        }
    }
}
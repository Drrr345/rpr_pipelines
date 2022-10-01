/**
 * Class which provides a configuration of some pipeline
 */
public class PipelineConfiguration {

    List supportedOS
    Map productExtensions
    String artifactNameBase
    String buildProfile
    String testProfile
    Map displayingProfilesMapping

    /**
     * Main constructor
     *
     * @param supportedOS list of supported OS
     * @param productExtensions map with extension of product for each OS
     * @param artifactNameBase the name of the artifact without OS name / version. It must be same for any OS / version
     */
    PipelineConfiguration(Map params) {
        this.supportedOS = params["supportedOS"]
        this.productExtensions = params["productExtensions"]
        this.artifactNameBase = params["artifactNameBase"]

        if (params.containsKey("buildProfile")) {
            this.buildProfile = params["buildProfile"]
        }

        if (params.containsKey("testProfile")) {
            this.testProfile = params["testProfile"]
        }

        if (params.containsKey("displayingProfilesMapping")) {
            this.displayingProfilesMapping = params["displayingProfilesMapping"]
        }
    }

}
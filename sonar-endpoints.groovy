import org.artifactory.repo.RepoPathFactory
import org.artifactory.search.Searches
import org.artifactory.search.aql.AqlResult
import org.jfrog.build.api.release.Promotion
import org.artifactory.api.build.BuildService
import groovy.json.JsonSlurper
import org.artifactory.build.BuildRun
import org.artifactory.build.DetailedBuildRun
import org.artifactory.exception.CancelException
import org.artifactory.resource.ResourceStreamHandle
import java.lang.reflect.Array

executions {
    //Expose a new endpoint for sonarqube webhook
    updateSonarTaskStatus(httpMethod: 'POST', users: ["admin"], groups: [], params:[targetRepo: '', sourceRepo: '', includeDep: 'false', rolledBackRepo: '']) { params, ResourceStreamHandle body ->

        targetRepo = getStringProperty(params, 'targetRepo', true)
        sourceRepo = getStringProperty(params, 'sourceRepo', false)
        rolledBackRepo = getStringProperty(params, 'rolledBackRepo', false)
        includeDep = params['includeDep'][0] ? params['includeDep'][0]  as boolean : false

        bodyJson = new JsonSlurper().parse(body.inputStream)
        buildService = ctx.beanForType(BuildService.class)
        //Get build object based on task id from json object
        sonarTaskId = bodyJson.taskId
        log.info "Processing SonarQube webhook for taskId " + sonarTaskId 
        Collection<String> propValues = [bodyJson.qualityGate.status]
            Map<String, Collection<String>> props = new HashMap<String,Collection<String>>()
            props.put("SONAR_RESULT", propValues)

        //very rare when the sonar scan takes a lot shorter than publish build info (to avoid BI not found). Also take care sonar webhooks expects answer in less than 10s
        //better way might be to use a threadpool in order to return response asap to sonar server and implement logic in background
        sleep( 5000 )
        def aql = "builds.find({\"@buildInfo.env.SONAR_CETASKID\":\"" + sonarTaskId + "\"})"
        searches.aql(aql.toString()) {
            AqlResult result ->
               result.each { b ->
                   //check quality gate result
                    if(bodyJson.status == 'SUCCESS')
                    {
                        log.info 'SonarQube quality gate ' + bodyJson.qualityGate.name + ' passed for build ' + b['build.name']
                        promotion = getPromotionInstance("STAGING", "Sonar quality gate successful", "admin", false, targetRepo, sourceRepo, false, true, true, props, true)
                    }
                    else
                    {
                        log.info 'SonarQube quality gate ' + bodyJson.qualityGate.name + ' failed for build ' + b['build.name']
                        promotion = getPromotionInstance("ROLLED-BACK", "Sonar quality gate failed", "admin", false, rolledBackRepo, sourceRepo, false, true, true, props, true)
                    }
                    List<BuildRun> buildsRun = builds.getBuilds(b['build.name'], b['build.number'],null)
                    def buildRun = buildsRun[0]
                    DetailedBuildRun detailedBuildRun = builds.getDetailedBuild(buildRun)
                    buildService.promoteBuild(detailedBuildRun, promotion)
               }
        }
    }
}

private String getStringProperty(params, pName, mandatory) {
    def key = params[pName]
    def val = key == null ? null : key[0].toString()
    return val
}


private Promotion getPromotionInstance(String status, String comment, String ciUser, boolean dryRun, String targetRepo, String sourceRepo, boolean copy, boolean artifacts, boolean dependencies, Map<String, Collection<String>> properties, boolean failFast) {
    log.warn "create promotion object"
    Promotion promotion = new Promotion();
    promotion.setStatus(status);
    promotion.setComment(comment);
    promotion.setCiUser(ciUser);
    promotion.setTimestamp('2018-02-11T18:30:24.825+0200');
    promotion.setDryRun(dryRun);
    promotion.setTargetRepo(targetRepo);
    promotion.setSourceRepo(sourceRepo);
    promotion.setCopy(copy);
    promotion.setArtifacts(artifacts);
    promotion.setDependencies(dependencies);
    def s = [] as Set<String>
    promotion.setScopes(s);
    promotion.setProperties(properties);
    promotion.setFailFast(failFast);
    log.warn "return promotion object"
    return promotion;
}
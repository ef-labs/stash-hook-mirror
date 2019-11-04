package com.englishtown.bitbucket.hook;

import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.command.GitCommandExitHandler;
import com.atlassian.utils.process.ProcessException;
import com.atlassian.utils.process.StringOutputHandler;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public class MirrorRemoteAdmin {
    private static final Logger log = LoggerFactory.getLogger(MirrorRemoteAdmin.class);
    private final PasswordEncryptor passwordEncryptor;
    private final I18nService i18nService;

    Client client;
    public MirrorRemoteAdmin(PasswordEncryptor passwordEncryptor,I18nService i18nService) {
        this.passwordEncryptor=passwordEncryptor;
        this.i18nService=i18nService;
        ClientConfig config=new DefaultClientConfig();
        this.client = Client.create(config);
    }
    private void addToStream(StringOutputHandler outputHandler,String text) {
        try {
            outputHandler.process(new ByteArrayInputStream(text.getBytes()));
        } catch (ProcessException e) {
            log.error("Failed to process response: "+e.getMessage());
        }
    }
    public void delete(MirrorSettings settings, Repository repository, StringOutputHandler outputHandler) {
        String plainPassword = passwordEncryptor.decrypt(settings.password);
        String plainPrivateToken = passwordEncryptor.decrypt(settings.privateToken);
        PasswordHandler passwordHandler = new PasswordHandler(plainPassword,plainPrivateToken,
                new GitCommandExitHandler(i18nService, repository));
        RuntimeException e=null;
        try {
            delete(settings, repository, passwordHandler);
        } catch (RuntimeException deleteException) {
            e=deleteException;
        }
        addToStream(outputHandler,passwordHandler.getOutput());
        if(e!=null) {
            throw e;
        }
    }
    private void delete(MirrorSettings settings, Repository repository, PasswordHandler passwordHandler) {
        ObjectMapper mapper = new ObjectMapper();
        String plainPrivateToken = passwordEncryptor.decrypt(settings.privateToken);

        if(settings.restApiURL.isEmpty()) {
            log.error("Remote REST Api URL not configured for "+ repository.getName());
            return;
        }
        WebResource webResource = client
                .resource(settings.restApiURL+"/api/v4/projects")
                .queryParam("search",repository.getName());
        if(!settings.privateToken.isEmpty()) {

            webResource=webResource.queryParam("private_token", plainPrivateToken);
        }

        ClientResponse response = webResource.accept(MediaType.APPLICATION_JSON)
                .get(ClientResponse.class);

        if (response.getStatus() != 200) {
            addToStream(passwordHandler,response.toString());
            throw new RuntimeException("Failed : HTTP error code : "
                    + response.getStatus());
        }
        JsonNode output;
        try {
            output = mapper.readTree(response.getEntityInputStream());
        } catch (IOException e){
            addToStream(passwordHandler,e.toString());
            throw new RuntimeException("Failed : Invalid response data from "+ settings.restApiURL + " : " + e.getMessage());
        }
        Integer repoId=null;
        for(JsonNode project: output) {
            if(project.get("path_with_namespace").asText().equals(repository.getProject().getKey() +"/"+repository.getName())) {
                repoId= project.get("id").asInt();
                break;
            }
        }
        if(repoId == null) {
            addToStream(passwordHandler, response.toString());
            throw new RuntimeException("Remote repository not found");
        }

        webResource = client
                .resource(settings.restApiURL+"/api/v4/projects/"+repoId);
        if(!settings.privateToken.isEmpty()) {
            webResource=webResource.queryParam("private_token", plainPrivateToken);
        }
        response = webResource.accept(MediaType.APPLICATION_JSON)
                .delete(ClientResponse.class);
        if (response.getStatus() != 202) {
            addToStream(passwordHandler,response.toString());
            throw new RuntimeException("Failed : HTTP error code : "
                    + response.getStatus());
        }
        addToStream(passwordHandler,response.toString());
    }
}

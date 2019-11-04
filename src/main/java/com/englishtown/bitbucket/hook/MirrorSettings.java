package com.englishtown.bitbucket.hook;

import java.io.Serializable;

class MirrorSettings implements Serializable {

    String mirrorRepoUrl;
    String username;
    String password;
    String suffix;
    String refspec;
    boolean tags;
    boolean notes;
    boolean atomic;
    String restApiURL;
    String privateToken;
}

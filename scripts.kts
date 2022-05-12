tasks {
    afterEvaluate {

    
    /**
     * Name: Sync Client Resources
     *
     * Operation: Ensures the output server resources include the client output files
     *
     * Requirement: Task buildClient building its output into ../client/build
     */
    val syncClient = register<Sync>("syncClientResources") {
        group = "client"
        dependsOn(buildClient)
        from("../client/build")
        into(sourceSets.main.get().output.resourcesDir!!.resolve("static"))
    }


        /**
         * Name: Upload to EC2
         *
         * Operation: Replaces the server jar in the EC2 instance with the output of shadowJar.
         *  Specifically, it copies the shadowJar into the EC2 instance using SCP, it then SSHs into the EC2, stops the running java process,
         *  deletes the old jar, and starts the new shadowJar.
         *
         * Requirements:
         * - Project property **ec2_domain** set to the domain name of the EC2 instance.
         * - Environment variable **EC2_KEYPAIR** set to the fully qualified path to a keypair file that can access the EC2.
         * - Using Shadow.
         */
        val uploadToEc2 = register("uploadToEc2") {
            group = "ec2"
            dependsOn(shadowJar)

            val shadowJarFiles = shadowJar.get().outputs.files
            val serverJar = shadowJarFiles.singleFile

            // We put it in a directory with a random id so it won't clash with the previous one.
            val randomId = Random.nextLong().absoluteValue.toString()
            // SCP doesn't support creating parent directories as needed, so we create the desired directory structure in this computer and copy
            // it wholesale to the ec2 instance.
            val serverDir = serverJar.toPath().parent.resolve(randomId)

            inputs.files(shadowJarFiles)
            outputs.dir(serverDir)

            val keyPair = System.getenv("EC2_KEYPAIR")
            val serverJarPath = serverJar.absolutePath
            val domain = project.property("ec2_domain").toString()
            val sshTarget = "ec2-user@$domain"
            val serverDirPath = serverDir.toFile().absolutePath
            val jarName = serverJar.name


            val sshCommandPrefix = "ssh -i $keyPair $sshTarget"
            val killCommand = "sudo killall java"

            val acDir = "ac"
            val fullAcDir = "~/$acDir"

            val scpCommand = "scp -r -i $keyPair $serverDirPath $sshTarget:$fullAcDir/"
            val relativeAcDir = "./$acDir"
            val removeCommand = "sudo find $relativeAcDir -type f -not -path \"$relativeAcDir/$randomId/*\" -delete"
            val cleanDirsCommand = "sudo find $fullAcDir -empty -type d -delete"
            val jarDir = "$fullAcDir/$randomId"
            val logFile = "$jarDir/output.txt"
            val javaCommand = "nohup sudo java -jar $jarDir/$jarName >$logFile 2>$logFile <$logFile &"

            // Kill old java process, remove all old files, delete empty directories
            val remoteCleanupCommand = "$killCommand ; $removeCommand && $cleanDirsCommand"

            // Split cleanup command and java -jar command so the task will exit properly
            val sshCommand1 = "$sshCommandPrefix \"$remoteCleanupCommand\""
            val sshCommand2 = "$sshCommandPrefix \"$javaCommand\""

            doFirst {
                println("Uploading server jar $serverJarPath to EC2 instance at $domain with id $randomId...")
                // Create the server jar directory that will be transferred to the server
                serverDir.toFile().mkdirs()
                serverJar.copyTo(serverDir.resolve(serverJar.name).toFile())

                println("Running '$scpCommand'")
                runCommand(scpCommand)
                println("Upload done, Running '$sshCommand1'")
                runCommand( sshCommand1)
                println("Cleanup done, Running '$sshCommand2'")
                runCommand( sshCommand2)
                println("Update successful.")
            }

        }
    }
}
import com.corposense.ConnectionInitializer
import com.corposense.Constants
import com.corposense.H2ConnectionDataSource
import com.corposense.models.Account
import com.corposense.handlers.AccountHandler
import com.corposense.handlers.OcrHandler
import com.corposense.handlers.SaveEditedTextHandler
import com.corposense.handlers.UploadDocHandler
import com.corposense.services.AccountService
import com.corposense.services.DirectoriesService
import com.fasterxml.jackson.databind.node.ObjectNode
import com.zaxxer.hikari.HikariConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ratpack.exec.Promise
import ratpack.hikari.HikariModule
import ratpack.http.MutableHeaders
import ratpack.http.Status
import ratpack.http.client.HttpClient
import ratpack.http.client.ReceivedResponse
import ratpack.http.client.RequestSpec
import ratpack.service.Service
import ratpack.service.StartEvent
import ratpack.thymeleaf3.ThymeleafModule
import java.nio.file.Path
import static ratpack.groovy.Groovy.ratpack
import static ratpack.thymeleaf3.Template.thymeleafTemplate as view

//import com.github.pemistahl.lingua.api.*
//import static com.github.pemistahl.lingua.api.Language.*

final Logger log = LoggerFactory.getLogger(ratpack)

final int FOLDER_ID = 4

/**
 * public/uploads path (use resolve() from relative path instead of absolutePath, the last one will be start from root disk)
 * It presents a full path on disk when running from Gradle, and relative path when running form a Jar file.
 */
String uploadDir = Constants.uploadDir
String publicDir = Constants.publicDir
String downloadsDir = Constants.downloadsDir
Path downloadPath = Constants.downloadPath
Path uploadPath = Constants.uploadPath

ratpack {
    serverConfig {
        development(true)
        port(3000)
        maxContentLength(26214400)  // 25Mb
    }
    bindings {
        module(ThymeleafModule)
        module( HikariModule, { HikariConfig config ->
            config.addDataSourceProperty("URL", "jdbc:h2:mem:account;INIT=CREATE SCHEMA IF NOT EXISTS DEV")
            config.dataSourceClassName = "org.h2.jdbcx.JdbcDataSource"
        })
        bind(H2ConnectionDataSource)
        bind(AccountService)
        bindInstance(Service, new ConnectionInitializer())
        bind(OcrHandler)
        bind(SaveEditedTextHandler)
        bind(UploadDocHandler)
        bind(AccountHandler)
        bind(DirectoriesService)

        add Service.startup('startup'){ StartEvent event ->
            event.registry.get(AccountService)
            if (serverConfig.development){
                String hostUrl = 'http://0.0.0.0:8080/logicaldoc'
                if (System.getenv('GITPOD_HOST')) {
                    hostUrl = 'http://127.0.0.1:8080'
                }
                sleep(500)
                event.registry.get(AccountService).create(new Account(
                                name: 'Main Server',
                                url: hostUrl,
                                username: 'admin',
                                password: 'admin',
                                active: true
                        )).then({ Integer id ->
                    log.info("Server N°: ${id} created.")
                })
            }

            [
                new File("${publicDir}/${uploadDir}"),
                new File("${publicDir}/${downloadsDir}")
            ].each { File baseUpload ->
                if (!baseUpload.exists()){
                    if (baseUpload.mkdirs()){
                        log.info("Created directory: ${baseUpload.absolutePath}")
                    } else {
                        log.error("Cannot create directory: ${baseUpload.absolutePath}")
                    }
                } else {
                    log.info("Directory: ${baseUpload} already exists.")
                }
            }

        }
    }
    handlers {

        // TODO: All endpoints should go to a server without rendering any UI
        prefix('api'){
            get('list'){ AccountService accountService, HttpClient client, DirectoriesService directoriesService  ->
                accountService.getActive().then({ List<Account> accounts ->
                    Account account = accounts[0]
                    if (accounts.isEmpty() || !account) {
                        response.status(Status.UNAUTHORIZED).send('No account provided.')
                    } else {
                        Serializable folderId = request.queryParams['folderId'] ?: FOLDER_ID
                        Promise<String> directoriesPromise = directoriesService.listDirectories(client,account.url,
                                account.username,
                                account.password,
                                folderId)
                        directoriesPromise.then { def directories ->
                            render(directories)
                        }
                    }
                })
            } // api/list

            get('search') { AccountService accountService, HttpClient client ->
                accountService.getActive().then({ List<Account> accounts ->
                    Account account = accounts[0]
                    if (accounts.isEmpty() || !account){
                        response.status(Status.NOT_FOUND).send('You must create a server account.')
                    } else {
                        def url = "${account.url}/services/rest/search/find".toURI()
                        def pattern = request.queryParams['pattern']?:''
                        def maxHits = request.queryParams['maxHits']?:10
                        def folderId = request.queryParams['folderId']?:FOLDER_ID
                        client.request(url, { RequestSpec reqSpec ->
                            reqSpec.basicAuth(account.username, account.password)
                            reqSpec.headers.set ("Accept", 'application/json')
                            reqSpec.method('POST').body { def body ->
                                body.text('{ "maxHits": '+maxHits+', "expression": "'+pattern+'", "expressionLanguage": "fr", "language": "fr", "folderId": '+folderId+' }')
                            }.headers { MutableHeaders headers ->
                                headers.set('Content-type','application/json')
                            }
                        }).then { ReceivedResponse res ->
                            response.contentType('application/json').send(res.body.text)
                        }
                    }
                })
            } // api/search

            get('documents/:folderId') { HttpClient client, AccountService accountService ->
                accountService.getActive().then({ List<Account> accounts ->
                    Account account = accounts[0]
                    if (accounts.isEmpty() || !account) {
                        response.status(Status.NOT_FOUND).send('You must create a server account.')
                    } else {
                        // List of documents
                        def folderId = pathTokens['folderId']?: FOLDER_ID
                        def url = "${account.url}/services/rest/document/listDocuments?folderId=${folderId}".toURI()
                        client.get(url) { RequestSpec reqSpec ->
                            reqSpec.basicAuth(account.username, account.password)
                            reqSpec.headers.set ("Accept", 'application/json')
                        }.then { ReceivedResponse res ->
                            res.forwardTo(response)
                        }
                    }
                })
            } // api/documents/:folderId
        }

        get { AccountService accountService, HttpClient client, DirectoriesService directoriesService  ->
            accountService.getActive().then({ List<Account> accounts ->
                Account account = accounts[0]
                if (accounts.isEmpty() || !account){
                    render(view("index", [message:'You must create a server account.']))
                } else {
//                    render(view('index', ['directories': null, 'account': null]))
                        Serializable folderId = request.queryParams['folderId'] ?: FOLDER_ID
                        Promise<String> directoriesPromise = directoriesService.listDirectories(client,account.url,
                                                                                                account.username,
                                                                                                account.password,
                                                                                                folderId)
//
                        directoriesPromise.then { directories ->
                            render(view('index', ['directories': directories, 'account': account]))
                        }
                        /*Promise<ObjectNode> folderStructurePromise = directoriesService.getFolderStructure(client,account.url,
                                account.username,
                                account.password,
                                folderId)
                        folderStructurePromise.then({ folderStructure ->
                            String json = folderStructure.toPrettyString()
                            log.info(json)
                        })*/
                }
            })
        }

        get('document') { HttpClient client, AccountService accountService ->
            accountService.getActive().then({ List<Account> accounts ->
                Account account = accounts[0]
                if (accounts.isEmpty() || !account) {
                    response.status(Status.NOT_FOUND).send('You must create a server account.')
                } else {
                    // Download a document
                    def url = "${account.url}/services/rest/document/getContent?docId=${request.queryParams['id']}".toURI()
                    client.get(url) { RequestSpec reqSpec ->
                        reqSpec.basicAuth(account.username, account.password)
                    }.then {  ReceivedResponse res ->
                        response.headers.set('Content-type', 'application/octet-stream')
                        response.send(res.body.bytes)
                    }
                }
            })

        } // GET: /document

        get('preview'){
            render(view('preview'))
        }

        all(chain(registry.get(SaveEditedTextHandler)))

        all(chain(registry.get(UploadDocHandler)))

        get("${uploadPath}/:imagePath"){
            response.sendFile(new File("${uploadPath}","${pathTokens['imagePath']}").toPath())
        }

        get("${downloadPath}/:filePath"){
            response.sendFile(new File("${downloadPath}","${pathTokens['filePath']}").toPath())
        }

        prefix('upload') {
            all(chain(registry.get(OcrHandler)))
        }

        prefix('server') {
            all(chain(registry.get(AccountHandler)))
        }

        get('search') {
            render(view('search'))
        }

        // Serve public files (assets...)
        files { dir publicDir }
    }
}

package umm3601.digitalDisplayGarden.Authentication;

// For interacting with Google OAuth

import com.github.scribejava.apis.GoogleApi20;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuth2AccessTokenErrorResponse;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.gson.Gson;
import com.nimbusds.jose.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.joda.time.DateTime;
import spark.ExceptionHandler;
import spark.utils.IOUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.*;

// For parsing the JWT from Google OAuth

public class Auth {

    // Google recommends that this URL be hardcoded into programs. (see: https://developers.google.com/identity/protocols/OpenIDConnect#discovery)
    private static final String googleOpenIDConfigurationEndpoint = "https://accounts.google.com/.well-known/openid-configuration";

    // Contains the clientId from Google API
    private final String clientId;

    // Contains the clientSecret from Google API
    private final String clientSecret;

    // Used for parsing converting to and from JSON
    private final Gson gson;

    // For authenticating callbacks
    private final OAuth20Service globalService;

    // hard-coded set of users that we will accept
    private Set<String> authUsers;

    // Public/Private Keypair for signing our own JSON Web Tokens
    private KeyPair keyPair;

    // the endpoint that Google will send users to after
    // authenticating them
    private final String callbackURL;

    private AuthWatchdogThread authorizeUserChangePoller = null;

    public Auth(String clientId, String clientSecret, String callbackURL) throws NoSuchAlgorithmException {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.gson = new Gson();
        this.authUsers = new TreeSet<>();

        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            Path path = Paths.get(".");
            WatchKey watchKey = path.register(watchService, ENTRY_CREATE);
            authorizeUserChangePoller = new AuthWatchdogThread(watchKey);
            authorizeUserChangePoller.start();
        } catch (IOException ioe) {
            System.err.println("WatchService could not be established!");
            System.err.println("authorized.users will not automatically be imported.");
        }

        try {
            String authFileLocation = "authorized.users";
            readAuthenticatedUsers(new FileInputStream(authFileLocation));
        } catch (FileNotFoundException fnfe) {
            System.err.println("authorized.users file not found!");
            System.err.println("Could not add users to initial set of authorized users");
            System.err.println("Create authorized.users and authorized users should be imported automatically.");
        }


        this.globalService = new ServiceBuilder()
                .apiKey(clientId)
                .apiSecret(clientSecret)
                .scope("email") // replace with desired scope
                .callback(callbackURL)
                .build(GoogleApi20.instance());

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        this.keyPair = keyPairGenerator.genKeyPair();
        this.callbackURL = callbackURL;
    }

    /**
     * @return A string containing a URL that we can send visitors to in order to authenticate them
     */
    public String getAuthURL(String originatingURL) {
        // I think we have to create a new service for every request we send out
        // since each one needs a different secretState
        final OAuth20Service service = new ServiceBuilder()
                .apiKey(clientId)
                .apiSecret(clientSecret)
                .scope("email") // replace with desired scope
                .state(generateSharedGoogleSecret(originatingURL))
                .callback(callbackURL)
                .build(GoogleApi20.instance());

        final Map<String, String> additionalParams = new HashMap<>();
        additionalParams.put("access_type", "offline");
        additionalParams.put("prompt", "consent");
        final String authorizationUrl = service.getAuthorizationUrl(additionalParams);

        return authorizationUrl;
    }

    /**
     * @param jwt - a JSON Web Token, signed by us, that contains the authorization
     *            info
     * @return true if this is a JWT token, that we signed, that is not expired, else return false
     */
    public boolean authorized(String jwt) {
        if (null == jwt) {
            // null JWTs are obviously invalid
            return false;
        }
        try {

            SignedJWT parsedState = SignedJWT.parse(jwt);
            JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) keyPair.getPublic());
            if (parsedState.verify(verifier)) {
                // the signature is valid, so check the expiration date
                TimeStampToken timeStampToken = gson.fromJson(parsedState
                        .getPayload()
                        .toJSONObject()
                        .toString(), TimeStampToken.class);
                DateTime exp = new DateTime(timeStampToken.exp);
                return exp.isAfterNow();
            } else {
                // cookie had invalid signature
                return false;
            }
        } catch (ParseException e) {
            // the cookie is not a valid jwt, reject
            return false;
        } catch (JOSEException e) {
            // the cookie "couldn't be verified"
            return false;
        }
    }

    public String generateCookieBody(int secondsToLive) {
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        JWSSigner signer = new RSASSASigner(privateKey);

        DateTime expDate = new DateTime((new Date()).getTime() + secondsToLive * 1000);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer("ddg")
                .claim("exp", expDate.toString())
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader(JWSAlgorithm.RS256),
                claimsSet
        );
        try {
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (JOSEException e) {
            e.printStackTrace();
            return "";
        }
    }


    public String generateSharedGoogleSecret(String originatingURL) {
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        JWSSigner signer = new RSASSASigner(privateKey);

        // Expire in 60 seconds
        DateTime expDate = new DateTime((new Date()).getTime() + 60 * 1000);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer("ddg")
                .claim("originatingURL", originatingURL)
                .claim("exp", expDate.toString())
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader(JWSAlgorithm.RS256),
                claimsSet
        );
        try {
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (JOSEException e) {
            e.printStackTrace();
            return "";
        }
    }

    /**
     * This method verifies that we signed the TimeStamp
     * token and returns the payload parsed into a RedirectToken
     *
     * @param state a JWT that we generated to send to Google
     * @return The parsed body of the JWT, or null if an error occurred
     */
    public RedirectToken unpackSharedGoogleSecret(String state) {
        try {
            SignedJWT parsedState = SignedJWT.parse(state);
            JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) keyPair.getPublic());
            if (parsedState.verify(verifier)) {
                return gson.fromJson(parsedState
                        .getPayload()
                        .toJSONObject()
                        .toString(), RedirectToken.class);

            } else {
                // failed signature
                return null;
            }
        } catch (ParseException e) {
            // the state couldn't be parsed, so it must be invalid
            return null;
        } catch (JOSEException e) {
            // we got a generic error when verifying the JWT, so it must be invalid
            return null;
        }
    }


    /**
     * Consumes the state and the code that Google gives to visitors, so
     * that they can in turn give them to us. This method confirms that
     * the state is one we issued and is non-expired. It then fetches
     * tokens from Google, confirms the signature (even though Google
     * says we don't need to work about it since we are fetching via
     * HTTPS), and user's email from the token.
     *
     * @param state should be a JWT signed by us
     * @param code  should be a "code" (whatever that means) from Google
     * @return a string with URL that the visitor was originally trying
     * to load. Null if the state or code are invalid in any way
     * or if we cannot contact Google to verify them.
     */
    public String verifyCallBack(String state, String code) throws UnauthorizedUserException, ExpiredTokenException {
        // parse the state and ensure its validity
        RedirectToken verifiedState = unpackSharedGoogleSecret(state);
        DateTime expTime = new DateTime(verifiedState.exp);
        if (expTime.isBeforeNow()) {
            // the user took too long to complete the authentication
            throw new ExpiredTokenException("The DDG token is expired");
        }

        try {
            // Use the callback code to get a token from Google with info
            // about the caller
            OAuth2AccessToken accessToken = globalService.getAccessToken(code);
            accessToken = globalService.refreshAccessToken(accessToken.getRefreshToken());

            // parse the token for the fields we want
            GoogleToken googleToken = gson.fromJson(accessToken.getRawResponse(), GoogleToken.class);

            // Get the URL for Google's OpenID description
            OpenIDConfiguration openIDConfiguration = getOpenIDConfiguration();

            // Confirm that the token is signed properly and extract the body as JSON
            String stringBody = parseAndValidate(googleToken.id_token, new URL(openIDConfiguration.jwks_uri));

            // Parse the JSON for the fields we care about
            GoogleJwtBody body = gson.fromJson(stringBody, GoogleJwtBody.class);

            // Confirm that the token is not expired
            if (new DateTime(body.exp * 1000).isBeforeNow()) {
                // Google is sending us bad tokens!!!
                return null;
            }
            // Confirm that the user is on our whitelist
            boolean authorized = userIsAuthorized(body.email);
            if (authorized) {
                return verifiedState.originatingURL;
            } else {
                throw new UnauthorizedUserException();
            }

        } catch (IOException | InterruptedException | ExecutionException e) {
            // these just generally mean that something went wrong
            // with interacting with Google's API
            e.printStackTrace();
            return null;
        } catch (OAuth2AccessTokenErrorResponse e) {
            //invalid client
            System.err.println("OAUTH2 ClientID was invalid!");
            System.err.println("If you just set up a new ClientID then google may still be trying to process things.");
            System.err.println("Otherwise make sure that there is no extra whitespace after the clientID or secretKey in config.properties");
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Consumes a JWT signed by Google and a URL to the location
     * where Google publishes their public keys. Verifies the signature
     * on the JWT and returns the payload decoded into normal JSON
     *
     * @param jwt        A JTW signed by Google
     * @param keyOptions The URL to where Google publishes its public keys
     * @return the payload of the JWT as normal JSON or null if some sort of error occurred
     */
    private String parseAndValidate(String jwt, URL keyOptions) {

        try {
            ConfigurableJWTProcessor jwtProcessor = new DefaultJWTProcessor();
            JWKSource keySource = new RemoteJWKSet(keyOptions);
            JWSAlgorithm expectedJWSAlg = JWSAlgorithm.RS256; // should this really be hardcoded?
            JWSKeySelector keySelector = new JWSVerificationKeySelector(expectedJWSAlg, keySource);
            jwtProcessor.setJWSKeySelector(keySelector);

            SecurityContext ctx = null; // optional context parameter, not required here
            JWTClaimsSet claimsSet = jwtProcessor.process(jwt, null);

            return claimsSet.toJSONObject().toString();
        } catch (ParseException e) {
            // the string couldn't be parsed to to a syntactically valid JWT
            e.printStackTrace();
            return null;
        } catch (BadJOSEException e) {
            // the (signature on the) JWT is rejected?
            e.printStackTrace();
            return null;
        } catch (JOSEException e) {
            // generic exception that the library throws seemingly everywhere
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Fetches the Google's OpenID configuration and parses
     * it the fields we care about (currently only one)
     *
     * @return a new OpenIDConfiguration object that contains the URL from which we can fetch Google's public keys
     * @throws IOException when something goes wrong with with fetching the data
     */
    public OpenIDConfiguration getOpenIDConfiguration() throws IOException {
        try {
            InputStream in = new URL(googleOpenIDConfigurationEndpoint).openStream();
            return gson.fromJson(IOUtils.toString(in), OpenIDConfiguration.class);
        } catch (MalformedURLException e) {
            System.out.println("Your dev is an idiot. Authentication will fail because we could not verify Google's signatures.");
            return null;
        }
    }

    /**
     * Checks to see if the email is in our list of
     * addresses that have access to the system.
     *
     * @param email A string containing an email address
     * @return True iff the email is in our list
     */
    public boolean userIsAuthorized(String email) {
        return authUsers.contains(email);
    }

    /**
     * Returns a cookie object with a time-to-live
     * of 24 hours. The body of the cookie is simply
     * a signed JWT that has an expiration date baked
     * into it.
     *
     * @return the new Cookie
     */
    public Cookie getCookie() {
        // 12 hours * 60 minutes/hour * 60 seconds/minute = 43200 seconds
        int timeToLive = 43200;
        String cookieBody = generateCookieBody(timeToLive);
        Cookie c = new Cookie("ddg", cookieBody, timeToLive);

        return c;
    }

    private void readAuthenticatedUsers(InputStream authFileStream) {
        //Read (buffer) users into a set, then commit to them by writing to authUsers
        Set<String> out = new TreeSet<String>();
        try {
            Scanner reader = new Scanner(authFileStream);
            String token;

            //If you ever encounter "ALLOW", the following token is expected to be
            //a valid google-affiliated email
            while (reader.hasNext()) {
                token = reader.next();
                if (token.equals("ALLOW")) {
                    String authorizedUser = reader.next();
                    out.add(authorizedUser);
                    System.out.println("Authorizing " + authorizedUser);
                }
            }
        } catch (NoSuchElementException e) {
            System.err.println("Authorized User file is corrupt!");
            System.err.println("Expected: email address. Found: end of file");
            e.printStackTrace();
            throw e;
        }
        authUsers = out;
    }

    //https://stackoverflow.com/questions/16251273/can-i-watch-for-single-file-change-with-watchservice-not-the-whole-directory
    private class AuthWatchdogThread extends Thread {
        WatchKey key;
        AtomicBoolean running;

        private AuthWatchdogThread(WatchKey wk) {
            this.key = wk;
        }

        public void end() //Come to think of it, there really isn't any clean way to end the server
        {
            running.set(false);
        }

        @Override
        public void run() {
            System.out.println("Authorized User Watchdog Service Started");
            running = new AtomicBoolean(true);
            while (running.get()) {
                try {
                    Thread.sleep(15000); //Every 15 seconds sounds fine enough for this system.
                }
                catch(InterruptedException ie)
                {
                    System.err.println("Auth Watchdog's sleep was interrupted. Odd, but not harmful.");
                }

                //key.pollEvents() is not blocking, so it's meaningful to terminate the
                //previous while that way. Let's add a sleep so we're not polling for events
                //every couple microseconds...

                for (WatchEvent<?> event : key.pollEvents()) {

                    final Path changed = (Path) event.context();
//                    System.out.println("Event: " + event.kind().toString());
//                    System.out.println("Target: " + changed.toString());

                    //Links to files will have a tilde appended
                    if (changed.toString().equals("authorized.users") || changed.toString().equals("authorized.users~")) {
                        System.out.println("Authorized Users file has changed.");
                        try {
                            readAuthenticatedUsers(new FileInputStream("authorized.users"));
                        } catch (IOException ioe) {
                            System.err.println("Failed to read authenticated users!");
                            ioe.printStackTrace();
                        }
                    }
                    // reset the key
                    boolean valid = key.reset();
                    if (!valid) {
                        System.out.println("Key has been unregistered");
                    }
                }
            }
        }
    }

}
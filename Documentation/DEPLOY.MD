# DEPLOY.md
Instructions on deploying this project in production mode.

## Pre-Deploy  
**Note:** You will need to install *nginx* both locally and on the production environment before deploying. *nginx* is a tool that acts as a proxy server that is needed for authentication to work.   
1. Make sure tests pass locally on server and client via *Gradle*  
2. Build and run the project locally with `./buildAndLaunchProject.sh`(**Not on the droplet**)  
  *  `cd` into the project directory
  * `chmod+x buildAndLaunchProject.sh`
  * `./buildAndLaunchProject.sh`  
  * Go to `http://localhost:2538`, and make sure your code works.  

## :droplet: On the droplet :droplet:  
### Increasing Swap Memory  
When we build our code on the droplet, we build the project with tests,
this is to ensure that everything works just as well on the droplet as it does locally.
The problem is that a lot of our tests use substantial amounts of memory, and because our droplet is a virtual machine with restricted amounts of memory, some times we get `java.lang.OutOfMemoryError:`'s. To cope with this, we increase our swap memory from **3GB** to **6.5GB**. Here is how you can too!
  * You need to login on your droplet as `root`, and open a terminal. Run the following commands  
  * `free -h`: This shows you how much swap memory you have.   
  ![free -h](Graphics/freeh.png)  
   ``` bash   
   swapoff /swapfile  
   fallocate -l 6.5G /swapfile  
   chmod 600 /swapfile  
   mkswap /swapfile  
   swapon /swapfile
   ```

`free -h` again and you should see that your swap memory should have gone from 3gb to 6.5gb.   

### Create config and authentication files for Server
In order for our authentication to work, you need to create several files: namely `config.properties`, `config.properties.deployment`, and `authorized.users`.

Looking in the server directory, you should see `example.authorized.users` and `example.config.properties`. These files are templates with fake information, you can use these files to help make these configuration files. You need to create these files within `/server`. When you run the `buildAndLaunchProject.sh` script, `authorized.users` and `config.properties.deployment` will be soft linked into the appropriate places.

`config.properties` (without the `.deployment`) is the configuration used in the testing environment, `config.properties.deployment` is used when built in production. `authorized.users` contains the list of authorized users that are allowed access to the admin privileges.
Look in [ServerConfiguration.md](./ServerConfiguration.md) to see how to set up these files.

### Changing IP's and Keys in Client:
Several places in our code refer to `localhost`, and that might cause problems when deploying for public use. Change the following files to point to the right places.

#### Client - Webpack file:
*   `webpack.prod.js` : change `localhost` to the correct IP address.
Due to *https*, this would look something like `'https://public.url/api/'` (`'https://digital-display-garden.umn.edu/api'` for example)  

#### Client - `google-charts-component.html`   
In `google-charts-component.html`, we grab an image from the server to use as a background for the bubble chart. When running locally, we tell it to go to `localhost`, but for deployment, we need to change this to the correct IP address.  (`'http://public.url/api/admin/gardenPicture'`)   
#### Changing API Key - `index.html` (If you are using google-maps)
In previous versions of our product, we have used google maps. If you would like to use google maps, you need an *API Key*.
Because we never commit and push our **API Key**, we need to add it before deployment. You can generate an API key for google maps [here](https://developers.google.com/maps/documentation/javascript/get-api-key).
Once you have your API Key, place it in the following code block where it says **YOUR_KEY**  
```html
<script async defer src="https://maps.googleapis.com/maps/api/js?key=YOUR_KEY&callback=initMap"
  type="text/javascript"></script>
```
#### Set up NGINX for HTTPS:
See [Https.md](./Https.md)

## Starting the machine!  
Now we can start! This part is pretty simple. I made a bash script that will clean, build, gradle and start the server. I also normally use a cool tool called *tmux* to start a process, be able to walk away from deploy-user, come back, re-attach to the process, and see how the server is doing. Here is a [tmux cheat sheet](https://gist.github.com/henrik/1967800) for more info. Here are some easy steps to get the project up and running:
1. `cd` into the project directory
2. `tmux` to create a *tmux* session  
3. `chmod +x buildAndLaunchProject.sh` *buildAndLaunchProject.sh* is a bash script I made to clean, build, and start the server. chmod +x makes this file executible.
4. `./buildAndLaunchProject.sh` actually starts the script. Keep watching it until you see that the server has stared. Can easily take 5 minutes.
5. `ctrl b` and then `d` to disconnect from the *tmux* session.

And now your server is running!!! :boom:  

:octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus::octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus: :octopus:

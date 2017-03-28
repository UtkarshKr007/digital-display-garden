package umm3601.plant;

import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;
//import sun.text.normalizer.UTF16;
import umm3601.digitalDisplayGarden.Plant;
import umm3601.digitalDisplayGarden.PlantController;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static junit.framework.TestCase.assertEquals;

public class FilterByCommonName {


    private final static String databaseName = "data-for-testing-only";
    private PlantController plantController;

    @Before
    public void populateDB() throws IOException {
        PopulateMockDatabase db = new PopulateMockDatabase();
        db.clearAndPopulateDBAgain();
        plantController = new PlantController(databaseName);
    }

    @Test
    public void filterPlantsByCommonName() throws IOException {
        Plant[] filteredPlants;
        Gson gson = new Gson();

        Map<String, String[]> queryParams = new HashMap<>();
        queryParams.put("commonName", new String[]{"Alternanthera"});
        String rawPlants = plantController.listPlants(queryParams, "first uploadId");
        filteredPlants = gson.fromJson(rawPlants, Plant[].class);
        assertEquals("Incorrect number of plants with commonName Alternanthera", 1, filteredPlants.length);
        assertEquals("Incorrect commonName of plant", "Alternanthera", filteredPlants[0].commonName);
        assertEquals("Incorrect cultivar of plant", "Experimental", filteredPlants[0].cultivar);
//        if(rawPlants.contains(UTF16.valueOf(0x00AE))){
//            rawPlants = rawPlants.replaceAll(UTF16.valueOf(0x00AE), "");
//        }
    }

    @Test
    public void filterPlantsByPlantThatDoesntExist() throws IOException {
        Plant[] filteredPlants;
        Gson gson = new Gson();

        Map<String, String[]> queryParams = new HashMap<>();
        queryParams.put("commonName", new String[]{"Bob"});
        String rawPlants = plantController.listPlants(queryParams, "second uploadId");
        filteredPlants = gson.fromJson(rawPlants, Plant[].class);
        assertEquals("Incorrect number of plants with commonName Bob", 0, filteredPlants.length);
    }
}

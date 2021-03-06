package com.pokescanner.loaders;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.map.Map;
import com.pokegoapi.api.map.MapObjects;
import com.pokegoapi.api.map.fort.Pokestop;
import com.pokegoapi.auth.CredentialProvider;
import com.pokegoapi.auth.GoogleUserCredentialProvider;
import com.pokegoapi.auth.PtcCredentialProvider;
import com.pokegoapi.exceptions.AsyncPokemonGoException;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;
import com.pokescanner.events.ForceLogoutEvent;
import com.pokescanner.events.ScanCircleEvent;
import com.pokescanner.helper.PokemonListLoader;
import com.pokescanner.objects.FilterItem;
import com.pokescanner.objects.Gym;
import com.pokescanner.objects.PokeStop;
import com.pokescanner.objects.Pokemons;
import com.pokescanner.objects.User;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import POGOProtos.Map.Fort.FortDataOuterClass;
import POGOProtos.Map.Pokemon.MapPokemonOuterClass;
import io.realm.Realm;
import okhttp3.OkHttpClient;

public class ObjectLoaderPTC extends Thread {
    User user;
    List<LatLng> scanMap;
    int SLEEP_TIME;
    private Realm realm;
    int position;
    GoogleApiClient mGoogleWearApiClient;
    Context context;
    int progressBar;

    public ObjectLoaderPTC(User user, List<LatLng> scanMap, int SLEEP_TIME, int pos, GoogleApiClient mGoogleWearApiClient, Context context) {
        this.user = user;
        this.scanMap = scanMap;
        this.SLEEP_TIME = SLEEP_TIME;
        this.position = pos;
        this.mGoogleWearApiClient = mGoogleWearApiClient;
        this.context = context;
    }

    @Override
    public void run() {
        try {
            OkHttpClient client = new OkHttpClient();
            //Create our provider and set it to null
            CredentialProvider provider = null;
            //Is our user google or PTC?
            if (user.getAuthType() == User.GOOGLE) {
                if (user.getToken() != null) {
                    provider = new GoogleUserCredentialProvider(client, user.getToken().getRefreshToken());
                } else {
                    EventBus.getDefault().post(new ForceLogoutEvent());
                }
            } else {
                provider = new PtcCredentialProvider(client, user.getUsername(), user.getPassword());
            }

            if (provider != null) {
                int scanPos = 0;

                PokemonGo go = new PokemonGo(provider, client);

                if (go != null) {
                    for (LatLng pos : scanMap) {
                            go.setLatitude(pos.latitude);
                            go.setLongitude(pos.longitude);
                            Map map = go.getMap();
                            MapObjects event = map.getMapObjects();
                            final Collection<MapPokemonOuterClass.MapPokemon> collectionPokemon = event.getCatchablePokemons();
                            final Collection<FortDataOuterClass.FortData> collectionGyms = event.getGyms();
                            final Collection<Pokestop> collectionPokeStops = event.getPokestops();

                            EventBus.getDefault().post(new ScanCircleEvent(pos));

                            realm = Realm.getDefaultInstance();
                            realm.executeTransaction(new Realm.Transaction() {
                                @Override
                                public void execute(Realm realm) {
                                    for (MapPokemonOuterClass.MapPokemon pokemonOut : collectionPokemon)
                                        realm.copyToRealmOrUpdate(new Pokemons(pokemonOut));

                                    for (FortDataOuterClass.FortData gymOut : collectionGyms)
                                        realm.copyToRealmOrUpdate(new Gym(gymOut));

                                    for (Pokestop pokestopOut : collectionPokeStops)
                                        realm.copyToRealmOrUpdate(new PokeStop(pokestopOut));
                                }
                            });

                            SharedPreferences mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
                            SharedPreferences.Editor prefsEditor = mPrefs.edit();
                            progressBar = mPrefs.getInt("progressbar",0);
                            progressBar++;
                            prefsEditor.putInt("progressbar",progressBar);
                            prefsEditor.commit();

                            sendPokemonListToWear();
                            realm.close();
                            Thread.sleep(SLEEP_TIME);
                    }
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (RemoteServerException e) {
            e.printStackTrace();
        } catch (LoginFailedException e) {
            e.printStackTrace();
        }catch (AsyncPokemonGoException e) {
            e.printStackTrace();
        }
    }
    private void sendPokemonListToWear(){
        ArrayList<Pokemons> pokelist = new ArrayList<>(realm.copyFromRealm(realm.where(Pokemons.class).findAll()));
        ArrayList<Pokemons> listout = new ArrayList<>();




        for (int i = 0; i < pokelist.size(); i++) {
            Pokemons pokemons = pokelist.get(i);
            //IF OUR POKEMANS IS FILTERED WE AINT SHOWIN HIM
            if (!PokemonListLoader.getFilteredList().contains(new FilterItem(pokemons.getNumber()))) {

                //ADD OUR POKEMANS TO OUR OUT LIST
                listout.add(pokemons);
            }
        }


        Gson gson = new Gson();
        String json = gson.toJson(listout,new TypeToken<ArrayList<Pokemons>>() {}.getType());
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/pokemonlist");
        putDataMapReq.getDataMap().putString("pokemons", json);
        putDataMapReq.getDataMap().putInt("progressbar",progressBar);
        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        PendingResult<DataApi.DataItemResult> pendingResult =
                Wearable.DataApi.putDataItem(mGoogleWearApiClient, putDataReq);
    }


}


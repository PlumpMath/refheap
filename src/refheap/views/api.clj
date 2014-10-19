(ns refheap.views.api
  (:require [noir.session :as session]
            [me.raynes.laser :as laser]
            [clojure.java.io :refer [resource]]
            [refheap.models.api :as api]
            [refheap.models.paste :as paste]
            [refheap.models.users :as users]
            [compojure.core :refer [defroutes GET POST DELETE]]
            [refheap.views.common :refer [layout static]]))

(let [nodes (laser/parse-fragment (resource "refheap/views/templates/api.html"))
      api-head (static "refheap/views/templates/apihead.html")]
  (defn api-page []
    (layout
     (if-let [id (:id (session/get :user))]
       (laser/fragment nodes
                       (laser/id= "tokentext") (laser/content (api/get-token id))
                       (laser/id= "please-login") (laser/remove))
       (laser/fragment nodes (laser/id= "token") (laser/remove)))
     nil
     api-head)))

(defn generate-token []
  (when-let [id (:id (session/get :user))]
    (api/new-token id)))

(defn paste [{:keys [private contents language username token return]
              :or {private "false"}}
             remote-addr]
  (let [user (api/validate-user username token)]
    (if (string? user)
      (api/response :unprocessable user return)
      (let [paste (paste/paste
                    language
                    contents
                    (api/string->bool private)
                    (assoc user :remote-addr remote-addr))]
        (if (string? paste)
          (api/response :bad paste return)
          (api/response :created (api/process-paste paste) return))))))

(defn edit-paste [{:keys [id private contents language username token return]}]
  (if-let [paste (paste/get-paste id)]
    (let [user (api/validate-user username token)]
      (cond
       (string? user)
       (api/response :unprocessable user return)
       (nil? (:user paste))
       (api/response :unprocessable "You can't edit anonymous pastes." return)
       (nil? user)
       (api/response :unprocessable "You must be authenticated to edit pastes." return)
       (not= (:id user) (:user paste))
       (api/response :unprocessable "You can only edit pastes that you own." return)
       :else (let [paste (paste/update-paste paste
                                             (or language (:language paste))
                                             (or contents (:raw-contents paste))
                                             (if (nil? private)
                                               (:private paste)
                                               (api/string->bool private))
                                             user)]
               (if (string? paste)
                 (api/response :bad paste return)
                 (api/response :ok (api/process-paste paste) return)))))
    (api/response :not-found "Paste does not exist." return)))

(defn delete-paste [{:keys [id username token return]}]
  (if-let [paste (paste/get-paste id)]
    (let [user (api/validate-user username token)]
      (cond
       (string? user)
       (api/response :unprocessable user return)
       (nil? (:user paste))
       (api/response :unprocessable "You can't delete anonymous pastes." return)
       (nil? user)
       (api/response :unprocessable "You must be authenticated to delete pastes." return)
       (not= (:id user) (:user paste))
       (api/response :unprocessable "You can only delete pastes that you own." return)
       :else (api/response :no-content (paste/delete-paste id) return)))
    (api/response :not-found "Paste doesn't exist." return)))

(defn fork-paste [{:keys [id username token return]}]
  (if-let [paste (paste/get-paste id)]
    (let [user (api/validate-user username token)]
      (cond
       (string? user)
       (api/response :unprocessable user return)
       (= (:id user)
          (:user paste)) (api/response :unprocessable "You can't fork your own pastes." return)
       :else (api/response :created
                           (api/process-paste
                            (paste/paste (:language paste)
                                         (:raw-contents paste)
                                         (:private paste)
                                         user
                                         (:id paste)))
                           return)))
    (api/response :not-found "Paste doesn't exist." return)))

(defroutes api-routes
  (GET "/api" [] (api-page))
  (GET "/token/generate" [] (generate-token))
  (POST "/api/paste" {:keys [params remote-addr headers]}
    (paste params (or (get headers "x-forwarded-for") remote-addr)))
  (POST "/api/paste/:id" {:keys [params]}
    (edit-paste params))
  (DELETE "/api/paste/:id" {:keys [params]}
    (delete-paste params))
  (POST "/api/paste/:id/fork" {:keys [params]}
    (fork-paste params))
  (GET "/api/paste/:id" {{:keys [id return]} :params}
    (if-let [paste (paste/get-paste id)]
      (api/response :ok (api/process-paste paste) return)
      (api/response :not-found "Paste does not exist." return)))
  (GET "/api/paste/:id/highlight" {{:keys [id return]} :params}
    (if-let [paste (paste/get-paste id)]
      (api/response :ok {:content (:contents paste)} return)
      (api/response :not-found "Paste does not exist." return))))

package org.smartregister.chw.hiv.activity

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.google.gson.Gson
import com.nerdstone.neatformcore.domain.builders.FormBuilder
import com.nerdstone.neatformcore.domain.model.NFormViewData
import com.nerdstone.neatformcore.form.json.JsonFormBuilder
import com.nerdstone.neatformcore.form.json.JsonFormEmbedded
import org.joda.time.DateTime
import org.joda.time.Period
import org.json.JSONException
import org.json.JSONObject
import org.koin.core.inject
import org.smartregister.chw.hiv.HivLibrary
import org.smartregister.chw.hiv.R
import org.smartregister.chw.hiv.contract.BaseHivFormsContract
import org.smartregister.chw.hiv.dao.HivDao
import org.smartregister.chw.hiv.domain.HivMemberObject
import org.smartregister.chw.hiv.interactor.BaseHivFormsInteractor
import org.smartregister.chw.hiv.presenter.BaseHivFormsActivityPresenter
import org.smartregister.chw.hiv.util.Constants
import org.smartregister.chw.hiv.util.DBConstants
import org.smartregister.chw.hiv.util.JsonFormConstants
import org.smartregister.view.activity.SecuredActivity
import timber.log.Timber

/**
 * Created by cozej4 on 2020-05-13.
 *
 * @cozej4 https://github.com/cozej4
 *
 * This is the activity for loading hiv registration and followup JSON forms. It implements [BaseHivFormsContract.View]
 * and [StepperActions] (which is from the neat form library) that provides callback methods from the
 * form builder. It exposes a method to receiving the data from the views and exiting the activity
 */
open class BaseHivFormsActivity : SecuredActivity(), BaseHivFormsContract.View {

    protected var presenter: BaseHivFormsContract.Presenter? = null
    protected var baseEntityId: String? = null
    protected var formName: String? = null
    private var formBuilder: FormBuilder? = null
    private var jsonForm: JSONObject? = null
    private var useDefaultNeatFormLayout: Boolean? = null
    private lateinit var formLayout: LinearLayout
    private lateinit var mainLayout: LinearLayout
    private lateinit var sampleToolBar: Toolbar
    private lateinit var pageTitleTextView: TextView
    private lateinit var clientNameTitleTextView: TextView
    private lateinit var exitFormImageView: ImageView
    private lateinit var completeButton: TextView
    var hivMemberObject: HivMemberObject? = null
    val hivLibrary by inject<HivLibrary>()

    override fun onCreation() {
        setContentView(R.layout.activity_hiv_registration)
        mainLayout = findViewById(R.id.mainLayout)
        formLayout = findViewById(R.id.formLayout)
        sampleToolBar = findViewById(R.id.sampleToolBar)
        pageTitleTextView = findViewById(R.id.pageTitleTextView)
        clientNameTitleTextView = findViewById(R.id.clientNameTitleTextView)
        exitFormImageView = findViewById(R.id.exitFormImageView)
        completeButton = findViewById(R.id.completeButton)

        with(this.intent) {
            baseEntityId = getStringExtra(Constants.ActivityPayload.BASE_ENTITY_ID)
            formName = getStringExtra(Constants.ActivityPayload.HIV_REGISTRATION_FORM_NAME)
            useDefaultNeatFormLayout =
                getBooleanExtra(Constants.ActivityPayload.USE_DEFAULT_NEAT_FORM_LAYOUT, true)
            try {
                jsonForm = JSONObject(getStringExtra(Constants.ActivityPayload.JSON_FORM))
            } catch (e: JSONException) {
                Timber.e(e)
            }
            presenter = presenter()

            hivMemberObject =
                if (jsonForm!!.getString(JsonFormConstants.ENCOUNTER_TYPE) == Constants.EventType.HIV_COMMUNITY_FOLLOWUP_FEEDBACK) {
                    HivDao.getCommunityFollowupMember(baseEntityId!!)
                } else {
                    HivDao.getMember(baseEntityId!!)
                }

            with(presenter) {
                this?.initializeMemberObject(hivMemberObject!!)
                this?.fillClientData(hivMemberObject!!)
            }

            with(hivMemberObject!!) {
                val age = Period(DateTime(this.age), DateTime()).years
                clientNameTitleTextView.text =
                    "${this.firstName} ${this.middleName} ${this.lastName}, $age"

                pageTitleTextView.text =
                    jsonForm?.getJSONArray("steps")?.getJSONObject(0)?.getString("title") ?: ""
            }

            exitFormImageView.setOnClickListener(this@BaseHivFormsActivity::onClick)

            completeButton.setOnClickListener(this@BaseHivFormsActivity::onClick)

            createViewsFromJson()
        }
    }

    private fun onClick(it: View) {
        if (it.id == R.id.exitFormImageView) {
            showAlertDialog()
        }
        if (it.id == R.id.completeButton) {
            saveForm()
        }
    }

    private fun saveForm() {
        if (formBuilder?.getFormDataAsJson() != "") {
            val formData = formBuilder!!.getFormData()
            if (formData.isNotEmpty()) {
                if (jsonForm!!.getString(JsonFormConstants.ENCOUNTER_TYPE) == Constants.EventType.HIV_COMMUNITY_FOLLOWUP_FEEDBACK) {
                    //Saving referral form id
                    processReferralFeedbackForm(formData)
                }
                presenter!!.saveForm(formData, jsonForm!!)
                Timber.d("Saved Data = %s", Gson().toJson(formData))
                val intent = Intent()
                setDataToBePassedBackToCallingActivityAsResults(
                    intent,
                    jsonForm!!,
                    formData
                )
                setResult(RESULT_OK, intent)
                finish()
            }
            finish()
        }
    }

    private fun processReferralFeedbackForm(formData: HashMap<String, NFormViewData>) {
        formData[DBConstants.Key.COMMUNITY_REFERRAL_FORM_ID] =
            NFormViewData().apply {
                value = hivMemberObject!!.communityReferralFormId
            }

        //Saving chw names
        val allSharedPreferences = hivLibrary.context.allSharedPreferences()
        formData[DBConstants.Key.CHW_NAME] =
            NFormViewData().apply {
                value = allSharedPreferences.getANMPreferredName(
                    allSharedPreferences.fetchRegisteredANM()
                )
            }
    }

    private fun showAlertDialog() {
        AlertDialog.Builder(
            this@BaseHivFormsActivity,
            R.style.AlertDialogTheme
        )
            .setTitle(getString(R.string.confirm_form_close))
            .setMessage(getString(R.string.confirm_form_close_explanation))
            .setNegativeButton(R.string.yes) { _: DialogInterface?, _: Int -> finish() }
            .setPositiveButton(R.string.no) { _: DialogInterface?, _: Int ->
                Timber.d("Do Nothing exit confirm dialog")
            }
            .create()
            .show()
    }

    override fun onResumption() = Unit
    private fun createViewsFromJson() {
        try {
            val customLayouts = ArrayList<View>().also { list ->
                list.add(layoutInflater.inflate(R.layout.hiv_registration_form_view, null))
            }

            formBuilder = JsonFormBuilder(jsonForm.toString(), this)
            JsonFormEmbedded(
                formBuilder as JsonFormBuilder,
                formLayout
            ).buildForm(if (useDefaultNeatFormLayout!!) customLayouts else null)


        } catch (ex: JSONException) {
            Timber.e(ex)
        }
    }

    override fun presenter() = BaseHivFormsActivityPresenter(
        baseEntityId!!, this, BaseHivFormsInteractor()
    )


    override fun setProfileViewWithData() = Unit
    override fun setDataToBePassedBackToCallingActivityAsResults(
        intent: Intent,
        jsonForm: JSONObject,
        formData: HashMap<String, NFormViewData>
    ) {
        //TODO to be implemented where required
    }

}